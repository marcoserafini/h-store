package org.qcri.PartitioningPlanner.placement;

import java.util.ArrayList;
import java.util.List;

import org.jaga.definitions.*;
import org.jaga.individualRepresentation.greycodedNumbers.*;
import org.jaga.selection.*;

/**
 * TODO: Complete these comments.
 *
 * <p><u>Project:</u> JAGA - Java API for Genetic Algorithms.</p>
 *
 * <p><u>Company:</u> University College London and JAGA.Org
 *    (<a href="http://www.jaga.org" target="_blank">http://www.jaga.org</a>).
 * </p>
 *
 * <p><u>Copyright:</u> (c) 2004 by G. Paperin.<br/>
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, ONLY if you include a note of the original
 *    author(s) in any redistributed/modified copy.<br/>
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.<br/>
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *    or see http://www.gnu.org/licenses/gpl.html</p>
 *
 * @author Greg Paperin (greg@jaga.org)
 *
 * @version JAGA public release 1.0 beta
 */

public class GAPlacementFitness implements FitnessEvaluationAlgorithm {

	public GAPlacementFitness() {}
	
	Long coldPartitionWidth = 100000L; // redistribute cold tuples in chunks of 100000
	ArrayList<Long> tupleIds = null;
	ArrayList<Long> accesses = null; 
	ArrayList<Integer> locations = null; 
	ArrayList<List<Plan.Range>> slices = null;
	ArrayList<Long> sliceSizes = null;
	int tupleCount = 0;
	int sliceCount = 0;
	int placementCount = 0;
	Long totalAccesses = 0L;
	int partitionCount = 0;
	long meanAccesses = 0L;
	Long hottestTuple = 0L;

	public Class getApplicableClass() {
		return NDecimalsIndividual.class;
	}

	public Fitness evaluateFitness(Individual individual, int age, Population population, GAParameterSet params) {
		NDecimalsIndividual indiv = (NDecimalsIndividual) individual;
		ArrayList<Integer> newLocations = new ArrayList<Integer>();
		for(int i = 0; i < indiv.getSize(); ++i) {
			newLocations.add((int) indiv.getDoubleValue(i)); 
		}
		
		// calculate the bandwidth cost of this placement
		long cost = 0;
		for(int i = 0; i < placementCount; ++i) {
			if(i < tupleCount) {
				// we are moving individual hot tuples so cost is at most 1
				cost += ((locations.get(i) == newLocations.get(i)) ? 0 : 1); 
			}
			else {
				// we are moving slices of cold tuples 
				cost += ((locations.get(i) == newLocations.get(i)) ? 0 : sliceSizes.get(i - tupleCount)); 
			}
		}
		
		// initialize load to 0
		ArrayList<Long> loadPerPartition = new ArrayList<Long>();
		for(int i = 0; i < partitionCount; ++i) {
			loadPerPartition.add(0L);
		}
		
		// calculate the load on each partition with the new locations
		for(int i = 0; i < placementCount; ++i) {
			int partition = newLocations.get(i);
			loadPerPartition.set(partition, loadPerPartition.get(partition) + accesses.get(i));
		}
		
		// calculate the total load deviation over all partitions
		long loadDeviation = 0;
		for(Long load : loadPerPartition) {
			loadDeviation += (load > Math.max(meanAccesses, hottestTuple) * 1.05 ? load - meanAccesses : 0);
		}
		
		// seriously penalize plans that deviate from a balanced load
		long f = (long) (cost + 100000000000.0 * loadDeviation/totalAccesses);
		
		Fitness fit = new AbsoluteFitness(-f);
		return fit;
	}
	
	public void initialize(ArrayList<Long> tupleIds,
			ArrayList<Long> accesses,
			ArrayList<Integer> locations,
			ArrayList<List<Plan.Range>> slices,
			ArrayList<Long> sliceSizes,
			int tupleCount,
			int sliceCount,
			Long totalAccesses,
			int partitionCount,
			Long hottestTuple) {
		this.tupleIds = tupleIds;
		this.accesses = accesses;
		this.locations = locations;
		this.slices = slices;
		this.sliceSizes = sliceSizes;
		this.tupleCount = tupleCount;
		this.sliceCount = sliceCount;
		this.totalAccesses = totalAccesses;	
		this.placementCount = tupleCount + sliceCount;
		this.partitionCount = partitionCount;
		this.hottestTuple = hottestTuple;
		this.meanAccesses = totalAccesses/partitionCount;
	}

}
