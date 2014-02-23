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


#ifndef MIGRATIONMANAGER_H
#define MIGRATIONMANAGER_H

#include "catalog/database.h"
#include "common/tabletuple.h"
#include "indexes/tableindex.h"
#include "storage/tableiterator.h"

namespace voltdb {

class Table;
class TableIndex;
class PersistentTable;
class ExecutorContext;

class MigrationManager {
        
public: 
    MigrationManager(ExecutorContext *executorContext, catalog::Database *catalogDatabase);
    ~MigrationManager();

    /**
     * Extract a range from the given table
     * TODO: This is just a proposal and not what the real API should be...
     */
    Table* extractRange(PersistentTable *table, const NValue minKey, const NValue maxKey, int32_t requestTokenId, int32_t extractTupleLimit, bool& moreData);
    Table* extractRanges(PersistentTable *table, TableIterator& inputIterator, TableTuple& extractTuple, int32_t requestTokenId, int32_t extractTupleLimit, bool& moreData);
    TableIndex* getPartitionColumnIndex(PersistentTable *table);
    
    bool confirmExtractDelete(int32_t requestTokenId);
    bool undoExtractDelete(int32_t requestTokenId);
    
private:
    ExecutorContext *m_executorContext;
    catalog::Database *m_catalogDatabase;
    
    // map catalog reconfig/migration requestTokenIds to pointers of data tables that have been migrated
    std::map<int32_t, Table*> m_extractedTables;
    // map catalog reconfig/migration requestTokenIds to TableIds. used to undo a migration
    std::map<int32_t, std::string> m_extractedTableNames;
    std::map<std::string, int32_t> m_timingResults;
    
    
}; // MigrationManager class


    
}

#endif
