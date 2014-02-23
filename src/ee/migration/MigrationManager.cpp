/* Copyright (C) 2013 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include "migration/MigrationManager.h"
#include "common/types.h"
#include "common/debuglog.h"
#include "storage/table.h"
#include "storage/persistenttable.h"
#include "storage/tablefactory.h"

#include <string>
#include <vector>

#ifndef EXTRACT_STAT_ENABLED
#define EXTRACT_STAT_ENABLED
#endif

#ifdef EXTRACT_STAT_ENABLED
#include "boost/timer.hpp"
#endif

namespace voltdb
{
            
// -----------------------------------------
// MigrationManager Implementation 
// -----------------------------------------
    
MigrationManager::MigrationManager(ExecutorContext *executorContext, catalog::Database *catalogDatabase) :
    m_executorContext(executorContext),
    m_catalogDatabase(catalogDatabase) {
    #ifdef EXTRACT_STAT_ENABLED
    VOLT_INFO("EXTRACT_STAT_ENABLED");
    m_timingResults.clear();
    #endif

    // TODO: Precompute all of the stuff that we need for each table
    
    m_extractedTables.clear();
    m_extractedTableNames.clear();
}

MigrationManager::~MigrationManager() {
    // TODO
}

Table* MigrationManager::extractRange(PersistentTable *table, const NValue minKey, const NValue maxKey, int32_t requestToken, int32_t extractTupleLimit, bool& moreData) {
    VOLT_DEBUG("ExtractRange %s %s - %s ", table->name().c_str(),minKey.debug().c_str(),maxKey.debug().c_str() );        
    //Get the right index to use
    //TODO andy ae this should be cached on initialization. do tables exists? when should migration mgr be created? should it exist in dbcontext
    
    TableIndex* partitionIndex = getPartitionColumnIndex(table);    
    int partitionColumn = table->partitionColumn();
    bool partitionColumnIsIndexed=true;
    if(partitionIndex == NULL){
	VOLT_DEBUG("partitionColumn is not indexed partitionColumn: %d",partitionColumn);
        //TODO ae what do we do when we have no index for the partition colum?
        partitionColumnIsIndexed = false;
    } else {
	VOLT_DEBUG("partitionColumn is indexed partitionColumn: %d",partitionColumn);
    }
    TableTuple tuple(table->schema());
    //TODO ae andy -> How many byes should we set this to? Below is just a silly guess
    int outTableSizeInBytes = 1024; 
    //int outTableSizeInBytes = (maxKey.op_subtract(minKey)).castAs(VALUE_TYPE_INTEGER).getInteger() *tuple.maxExportSerializationSize();

    //output table
    Table* outputTable = reinterpret_cast<Table*>(TableFactory::getCopiedTempTable(table->databaseId(),
            table->name(),table,&outTableSizeInBytes));
    
    //Extract Limit 
    bool dataLimitReach = false;
    int tuplesExtracted = 0;
    
    std::vector<ValueType> keyColumnTypes(1, minKey.getValueType());
    std::vector<int32_t> keyColumnLengths(1, NValue::getTupleStorageSize(minKey.getValueType()));
    std::vector<bool> keyColumnAllowNull(1, true);
    TupleSchema* keySchema = TupleSchema::createTupleSchema(keyColumnTypes,keyColumnLengths,keyColumnAllowNull,true);
    TableTuple searchkey(keySchema);
    searchkey.move(new char[searchkey.tupleLength()]);
    searchkey.setNValue(0, minKey);
    #ifdef EXTRACT_STAT_ENABLED
    boost::timer timer;
    int rowsExamined = 0;
    #endif
    //Do we have a single key to pull
    if(minKey.compare(maxKey)==0 && partitionColumnIsIndexed){
	VOLT_DEBUG("Pulling single key on partitionedColumn");
        bool found = partitionIndex->moveToKey(&searchkey);    
        if(found){
            VOLT_INFO("Found");
            while(!(tuple = partitionIndex->nextValueAtKey()).isNullTuple()){
		#ifdef EXTRACT_STAT_ENABLED
		rowsExamined++;
		#endif
		if (dataLimitReach == true){
		  moreData = true;
		  break;
		}		
                if (!outputTable->insertTuple(tuple))
                {
                    VOLT_ERROR("Failed to insert tuple from table '%s' into"
                            " output table '%s'",
                            table->name().c_str(),
                            outputTable->name().c_str());
                    return NULL;
                }                
                table->deleteTuple(tuple,true);
		//Count if we have taken the max tuples
		if (++tuplesExtracted >= extractTupleLimit){
		  dataLimitReach = true;
		}		
            }           
        }
        else{
            VOLT_INFO("key not found for single key extract");       
        }
    } else if(minKey.compare(maxKey)<=0){
        //TODO ae andy -> on searching and checking for the max key condition
            // (cont) should we be using an expression or ok to just do  value check end value on iteration?
        //IF b-Tree
        if (partitionColumnIsIndexed && partitionIndex->getScheme().type == BALANCED_TREE_INDEX){            
            //We have a range to check
	    VOLT_DEBUG("Pulling one or more keys on partitionedColumn BTREE");
            partitionIndex->moveToKeyOrGreater(&searchkey);    
            while(((!(tuple = partitionIndex->nextValueAtKey()).isNullTuple()) ||
            (!(tuple = partitionIndex->nextValue()).isNullTuple())) && (maxKey.compare(tuple.getNValue(partitionColumn)) >0)){                
                #ifdef EXTRACT_STAT_ENABLED
		rowsExamined++;
		#endif
		
		//Have we reached our datalimit and found another tuple
		if (dataLimitReach == true){
		    VOLT_DEBUG("more tuples with limit");
		    moreData = true;
		    break;
		}
		
                if (!outputTable->insertTuple(tuple))
                {
                    VOLT_ERROR("Failed to insert tuple from table '%s' into  output table '%s'",table->name().c_str(),outputTable->name().c_str());
                    return NULL;
                }
                table->deleteTuple(tuple,true);
		//Count if we have taken the max tuples
		if (++tuplesExtracted >= extractTupleLimit){
		      VOLT_DEBUG("tuple limit reached b-tree");
		      dataLimitReach = true;
		}
            }
        }  // Else if hash index
        else if (!partitionColumnIsIndexed || partitionIndex->getScheme().type == HASH_TABLE_INDEX
	  ||  partitionIndex->getScheme().type == ARRAY_INDEX) {
            //find key
            VOLT_DEBUG("Pulling one or more key on partitionedColumn hash or array or non-indexed partition column");
            //TODO ae andy -> assume we cannot leverage anything about hashing with ranges, correct?
            //Iterate through results
            TableIterator iterator(table);           
            while (iterator.next(tuple))
            {
		#ifdef EXTRACT_STAT_ENABLED
		rowsExamined++;
		#endif
                //Is the partitionColumn in the range between min inclusive and max exclusive
		//VOLT_DEBUG("Val:%s  MinKey:%s MaxKey:%s MinCompare:%d MaxCompare:%d", tuple.getNValue(partitionColumn).debug().c_str(), minKey.debug().c_str(), maxKey.debug().c_str(), minKey.compare(tuple.getNValue(partitionColumn)), maxKey.compare(tuple.getNValue(partitionColumn))); 
		//MinKey <= val && (maxKey > val || (min==max && maxKey == val))
                if ((minKey.compare(tuple.getNValue(partitionColumn)) <= 0) && 
		  ((maxKey.compare(tuple.getNValue(partitionColumn)) > 0) || ((minKey.compare(maxKey) == 0) && maxKey.compare(tuple.getNValue(partitionColumn)) == 0))){
                  
		  //Have we reached our datalimit and found another tuple
		  if (dataLimitReach == true){
		      VOLT_DEBUG("more tuples with limit");
		      moreData = true;
		      break;
		  }		  
		  
		  if (!outputTable->insertTuple(tuple))
                    {
                        VOLT_ERROR("Failed to insert tuple from table '%s' into  output table '%s'",table->name().c_str(),outputTable->name().c_str());
                        return NULL;
                    }
                    table->deleteTuple(tuple,true);
		    //Count if we have taken the max tuples
		    if (++tuplesExtracted >= extractTupleLimit){
			VOLT_DEBUG("tuple limit reached - hash table %d", tuplesExtracted);
			dataLimitReach = true;
		    }
                }
            }
        } else {
	    VOLT_ERROR("Unsupported Index type");
            throwFatalException("Unsupported Index type %d",partitionIndex->getScheme().type );
        }     
    } else {
        //Min key should never be greater than maxKey        
        throwFatalException("Max extract key is smaller than min key");
    } 
    VOLT_DEBUG("Tuples extracted: %d, Rows examined: %d  Output Table %s",tuplesExtracted, rowsExamined, outputTable->debug().c_str());
    m_extractedTables[requestToken] = outputTable;
    m_extractedTableNames[requestToken] = table->name();
    #ifdef EXTRACT_STAT_ENABLED
    VOLT_INFO("ExtractRange %s %s - %s ", table->name().c_str(),minKey.debug().c_str(),maxKey.debug().c_str() );        
    //VOLT_INFO("Extraction Time: %.2f sec. Examined Tuples:%ld Active Tuples: %ld  Approximate Size to serialized: %ld", timer.elapsed(), rowsExamined, outputTable->activeTupleCount(), outputTable->getApproximateSizeToSerialize());
    
    std::string extract_id = "Extract:"+table->name()+" Range:"+minKey.debug().c_str()+"-"+maxKey.debug().c_str();
    m_timingResults[extract_id] = (int32_t)timer.elapsed();
    timer.restart();	
    #endif
    return outputTable;
}

Table* MigrationManager::extractRanges(PersistentTable *table, TableIterator& inputIterator, TableTuple& extractTuple, int32_t requestToken, int32_t extractTupleLimit, bool& moreData)
{
  std::map<NValue,NValue,NValue::ltNValue> rangeMap;
  NValue firstMin;
  NValue firstMax;
  firstMin.setNull();
  firstMax.setNull();
  while(inputIterator.next(extractTuple)) {
    NValue minKey = extractTuple.getNValue(2);
    NValue maxKey = extractTuple.getNValue(3);
    rangeMap[maxKey] = minKey; // we have to do it this way because of the way std::map::upper_bound() works
    VOLT_DEBUG("ExtractRange %s %s - %s ", table->name().c_str(),minKey.debug().c_str(),maxKey.debug().c_str() );
    if(firstMin.isNull() || firstMax.isNull()) {
      firstMin = minKey;
      firstMax = maxKey;
    }
  }

    //Get the right index to use
    //TODO andy ae this should be cached on initialization. do tables exists? when should migration mgr be created? should it exist in dbcontext
    
    TableIndex* partitionIndex = getPartitionColumnIndex(table);    
    int partitionColumn = table->partitionColumn();
    bool partitionColumnIsIndexed=true;
    if(partitionIndex == NULL){
	VOLT_DEBUG("partitionColumn is not indexed partitionColumn: %d",partitionColumn);
        //TODO ae what do we do when we have no index for the partition colum?
        partitionColumnIsIndexed = false;
    } else {
	VOLT_DEBUG("partitionColumn is indexed partitionColumn: %d",partitionColumn);
    }

    TableTuple tuple(table->schema());
    //TODO ae andy -> How many byes should we set this to? Below is just a silly guess
    int outTableSizeInBytes = 1024; 
    //int outTableSizeInBytes = (maxKey.op_subtract(firstMin)).castAs(VALUE_TYPE_INTEGER).getInteger() *tuple.maxExportSerializationSize();

    //output table
    Table* outputTable = reinterpret_cast<Table*>(TableFactory::getCopiedTempTable(table->databaseId(),
            table->name(),table,&outTableSizeInBytes));
    
    //Extract Limit 
    bool dataLimitReach = false;
    int tuplesExtracted = 0;
    
    std::vector<ValueType> keyColumnTypes(1, firstMin.getValueType());
    std::vector<int32_t> keyColumnLengths(1, NValue::getTupleStorageSize(firstMin.getValueType()));
    std::vector<bool> keyColumnAllowNull(1, true);
    TupleSchema* keySchema = TupleSchema::createTupleSchema(keyColumnTypes,keyColumnLengths,keyColumnAllowNull,true);
    TableTuple searchkey(keySchema);
    searchkey.move(new char[searchkey.tupleLength()]);
    searchkey.setNValue(0, firstMin);
    #ifdef EXTRACT_STAT_ENABLED
    boost::timer timer;
    int rowsExamined = 0;
    #endif

    //Do we have a single key to pull
    if(rangeMap.size() == 1 && firstMin.compare(firstMax)==0 && partitionColumnIsIndexed){
	VOLT_DEBUG("Pulling single key on partitionedColumn");
        bool found = partitionIndex->moveToKey(&searchkey);    
        if(found){
            VOLT_INFO("Found");
            while(!(tuple = partitionIndex->nextValueAtKey()).isNullTuple()){
		#ifdef EXTRACT_STAT_ENABLED
		rowsExamined++;
		#endif
		if (dataLimitReach == true){
		  moreData = true;
		  break;
		}		
                if (!outputTable->insertTuple(tuple))
                {
                    VOLT_ERROR("Failed to insert tuple from table '%s' into"
                            " output table '%s'",
                            table->name().c_str(),
                            outputTable->name().c_str());
                    return NULL;
                }                
                table->deleteTuple(tuple,true);
		//Count if we have taken the max tuples
		if (++tuplesExtracted >= extractTupleLimit){
		  dataLimitReach = true;
		}		
            }           
        }
        else{
            VOLT_INFO("key not found for single key extract");       
        }
    } else { // we have more than one key to pull

      //TODO ae andy -> on searching and checking for the max key condition
      // (cont) should we be using an expression or ok to just do  value check end value on iteration?
        //IF b-Tree
        if (partitionColumnIsIndexed && partitionIndex->getScheme().type == BALANCED_TREE_INDEX){            
          for(std::map<NValue,NValue,NValue::ltNValue>::iterator it=rangeMap.begin(); it!=rangeMap.end(); ++it) {
	    NValue minKey = it->second;
	    NValue maxKey = it->first;
	    if(minKey.compare(maxKey) > 0) {
	      //Min key should never be greater than maxKey
	      throwFatalException("Max extract key is smaller than min key");
	    }
	    searchkey.setNValue(0, minKey);

	  //We have a range to check
	    VOLT_DEBUG("Pulling one or more keys on partitionedColumn BTREE");
            partitionIndex->moveToKeyOrGreater(&searchkey);    
            while(((!(tuple = partitionIndex->nextValueAtKey()).isNullTuple()) ||
            (!(tuple = partitionIndex->nextValue()).isNullTuple())) && (maxKey.compare(tuple.getNValue(partitionColumn)) >0)){                
                #ifdef EXTRACT_STAT_ENABLED
		rowsExamined++;
		#endif
		
		//Have we reached our datalimit and found another tuple
		if (dataLimitReach == true){
		    VOLT_DEBUG("more tuples with limit");
		    moreData = true;
		    break;
		}
		
                if (!outputTable->insertTuple(tuple))
                {
                    VOLT_ERROR("Failed to insert tuple from table '%s' into  output table '%s'",table->name().c_str(),outputTable->name().c_str());
                    return NULL;
                }
                table->deleteTuple(tuple,true);
		//Count if we have taken the max tuples
		if (++tuplesExtracted >= extractTupleLimit){
		      VOLT_DEBUG("tuple limit reached b-tree");
		      dataLimitReach = true;
		}
            }
	    if(moreData) {
	      break; // break out of the second loop
	    }
	  }
        }  // Else if hash index
        else if (!partitionColumnIsIndexed || partitionIndex->getScheme().type == HASH_TABLE_INDEX
	  ||  partitionIndex->getScheme().type == ARRAY_INDEX) {
            //find key
            VOLT_DEBUG("Pulling one or more key on partitionedColumn hash or array or non-indexed partition column");
            //TODO ae andy -> assume we cannot leverage anything about hashing with ranges, correct?
            //Iterate through results
            TableIterator iterator(table);

            while (iterator.next(tuple))
            {
		#ifdef EXTRACT_STAT_ENABLED
		rowsExamined++;
		#endif

		std::map<NValue,NValue,NValue::ltNValue>::iterator it = rangeMap.upper_bound(tuple.getNValue(partitionColumn)); 
		NValue minKey = it->second;
		NValue maxKey = it->first;
		if(minKey.isNull() || maxKey.isNull()) {
		  continue;
		}
		if(minKey.compare(maxKey) > 0) {
		  //Min key should never be greater than maxKey
		  throwFatalException("Max extract key is smaller than min key");
		}
            
		//Is the partitionColumn in the range between min inclusive and max exclusive
		if ((minKey.compare(tuple.getNValue(partitionColumn)) <= 0) && 
		  ((maxKey.compare(tuple.getNValue(partitionColumn)) > 0) || ((minKey.compare(maxKey) == 0) && maxKey.compare(tuple.getNValue(partitionColumn)) == 0))){
                  
		  //Have we reached our datalimit and found another tuple
		  if (dataLimitReach == true){
		      VOLT_DEBUG("more tuples with limit");
		      moreData = true;
		      break;
		  }		  
		  
		  if (!outputTable->insertTuple(tuple))
                    {
                        VOLT_ERROR("Failed to insert tuple from table '%s' into  output table '%s'",table->name().c_str(),outputTable->name().c_str());
                        return NULL;
                    }
                    table->deleteTuple(tuple,true);
		    //Count if we have taken the max tuples
		    if (++tuplesExtracted >= extractTupleLimit){
			VOLT_DEBUG("tuple limit reached - hash table %d", tuplesExtracted);
			dataLimitReach = true;
		    }
                }

            }
        } else {
	    VOLT_ERROR("Unsupported Index type");
            throwFatalException("Unsupported Index type %d",partitionIndex->getScheme().type );
        }     
    }

    VOLT_DEBUG("Tuples extracted: %d, Rows examined: %d  Output Table %s",tuplesExtracted, rowsExamined, outputTable->debug().c_str());
    m_extractedTables[requestToken] = outputTable;
    m_extractedTableNames[requestToken] = table->name();

    #ifdef EXTRACT_STAT_ENABLED
    NValue minKey = rangeMap.begin()->second;
    NValue maxKey = rangeMap.rbegin()->first;
    VOLT_INFO("ExtractRange %s %s - %s ", table->name().c_str(),minKey.debug().c_str(),maxKey.debug().c_str() );        
    //VOLT_INFO("Extraction Time: %.2f sec. Examined Tuples:%ld Active Tuples: %ld  Approximate Size to serialized: %ld", timer.elapsed(), rowsExamined, outputTable->activeTupleCount(), outputTable->getApproximateSizeToSerialize());
    std::string extract_id = "Extract:"+table->name()+" Range:"+minKey.debug().c_str()+"-"+maxKey.debug().c_str();
    m_timingResults[extract_id] = (int32_t)timer.elapsed();
    timer.restart();	
    #endif

    return outputTable;
}


TableIndex* MigrationManager::getPartitionColumnIndex(PersistentTable *table) {
    int partitionColumn = table->partitionColumn();
    std::vector<TableIndex*> tableIndexes = table->allIndexes();
    
    for (int i = 0; i < table->indexCount(); ++i) {
        TableIndex *index = tableIndexes[i];
        
        VOLT_DEBUG("Index %s ", index->debug().substr(0,20).c_str());
        //One column in this index
        if(index->getColumnCount() == 1) {
            if (index->getColumnIndices()[0] == partitionColumn){
                VOLT_DEBUG("Index matches");
                return index;
            }
        }
    }
    return NULL;
}


bool MigrationManager::confirmExtractDelete(int32_t requestTokenId) {
    if(m_extractedTables.find(requestTokenId) == m_extractedTables.end()){
        VOLT_DEBUG("confirmExtractDelte requestTokenId was not found");
        return false;
    }
    else {
        VOLT_DEBUG("confirmExtractDelete for token %d", requestTokenId);
        Table* migratedData = m_extractedTables[requestTokenId];
        migratedData->deleteAllTuples(true);
        delete migratedData;        
        m_extractedTables.erase(requestTokenId);
        m_extractedTableNames.erase(requestTokenId);
        return true;
    }      
    return false;
}
bool MigrationManager::undoExtractDelete(int32_t requestTokenId) {
    throwFatalException("Undo delete not implemented yet");
    return false;
}


}
