package edu.brown.benchmark.ycsb.distributions;


/**                                                                                                                                                                                
 * Copyright (c) 2010 Yahoo! Inc. All rights reserved.                                                                                                                             
 *                                                                                                                                                                                 
 * Licensed under the Apache License, Version 2.0 (the "License"); you                                                                                                             
 * may not use this file except in compliance with the License. You                                                                                                                
 * may obtain a copy of the License at                                                                                                                                             
 *                                                                                                                                                                                 
 * http://www.apache.org/licenses/LICENSE-2.0                                                                                                                                      
 *                                                                                                                                                                                 
 * Unless required by applicable law or agreed to in writing, software                                                                                                             
 * distributed under the License is distributed on an "AS IS" BASIS,                                                                                                               
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or                                                                                                                 
 * implied. See the License for the specific language governing                                                                                                                    
 * permissions and limitations under the License. See accompanying                                                                                                                 
 * LICENSE file.                                                                                                                                                                   
 */

import java.util.Random;
import java.util.TreeMap;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

/**
 * A generator of a zipfian distribution. It produces a sequence of items, such that some items are more popular than others, according
 * to a zipfian distribution. When you construct an instance of this class, you specify the number of items in the set to draw from, either
 * by specifying an itemcount (so that the sequence is of items from 0 to itemcount-1) or by specifying a min and a max (so that the sequence is of 
 * items from min to max inclusive). After you construct the instance, you can change the number of items by calling nextInt(itemcount) or nextLong(itemcount).
 * 
 * Note that the popular items will be clustered together, e.g. item 0 is the most popular, item 1 the second most popular, and so on (or min is the most 
 * popular, min+1 the next most popular, etc.) If you don't want this clustering, and instead want the popular items scattered throughout the 
 * item space, then set scrambled=true.
 * 
 * Be aware: initializing this generator may take a long time if there are lots of items to choose from (e.g. over a minute
 * for 100 million objects). This is because certain mathematical values need to be computed to properly generate a zipfian skew, and one of those
 * values (zeta) is a sum sequence from 1 to n, where n is the itemcount. Note that if you increase the number of items in the set, we can compute
 * a new zeta incrementally, so it should be fast unless you have added millions of items. However, if you decrease the number of items, we recompute
 * zeta from scratch, so this can take a long time. 
 *
 * The algorithm used here is from "Quickly Generating Billion-Record Synthetic Databases", Jim Gray et al, SIGMOD 1994.
 */
public class VaryingZipfianGenerator extends IntegerGenerator
{     
	public static final double ZIPFIAN_CONSTANT=0.99;
	public static final long ITEM_COUNT=10000000000L;
	public static final int DEFAULT_INTERVAL=-1; // default not varying
	public static final int DEFAULT_SHIFT=0; // default no shift

	/**
	 * The last time we changed the distribution (in milliseconds)
	 */
	long lastTime;
	
	/**
	 * The amount to shift the distribution each time
	 */
	long shift = DEFAULT_SHIFT;

	/**
	 * The total shift from the base distribution
	 */
	long totalShift = 0L;
	
	/**
	 * Number of items.
	 */
	long items = ITEM_COUNT;

	/**
	 * Min and max items
	 */
    long min = 0L; long max = ITEM_COUNT;
	
    /**
	 * time interval in (in milliseconds)
	 */
    long interval = DEFAULT_INTERVAL;
	
	/**
	 * Min item to generate.
	 */
	long base = 0L;
	
	/**
	 * The zipfian constant to use.
	 */
	double zipfianconstant = ZIPFIAN_CONSTANT;
	
	/**
	 * Computed parameters for generating the distribution.
	 */
	double alpha,zetan,eta,theta,zeta2theta;
	
	/**
	 * The number of items used to compute zetan the last time.
	 */
	long countforzeta;

    /**
     * Whether to scramble the distribution or not
	 */
    boolean scrambled = false;
    
    /**
     * Whether to mirror the zipfian skew so items on both sides of the hottest tuple are also hot
	 * Mirrored is intended to be used for the slowly varying skew
	 */
    boolean mirrored = false;
    
    /**
     * Whether to make the shift to a new distribution random
     */
    boolean randomShift = false;
    
    /**
     * Number of hot spots (k) in addition to the underlying zipfian skew 
     */
    int numHotSpots = 0;
    
    /**
     * Percentage of tuple accesses that go to the k hot tuples
     */
    double percentAccessHotSpots = 0.0;
       
    /**
     * Whether to make the location of the hot spots random
    */
    boolean randomHotSpots = false;
 
    /**
     * List of the k randomly selected hot spots
     */
    ArrayList<Long> hotSpots = new ArrayList<Long>();
    
    /**
     * Map random double to an item id
     */
    TreeMap<Double, Long> itemMap;
 
	
	/**
	 * Flag to prevent problems. If you increase the number of items the zipfian generator is allowed to choose from, this code will incrementally compute a new zeta
	 * value for the larger itemcount. However, if you decrease the number of items, the code computes zeta from scratch; this is expensive for large itemsets.
	 * Usually this is not intentional; e.g. one thread thinks the number of items is 1001 and calls "nextLong()" with that item count; then another thread who thinks the 
	 * number of items is 1000 calls nextLong() with itemcount=1000 triggering the expensive recomputation. (It is expensive for 100 million items, not really for 1000 items.) Why
	 * did the second thread think there were only 1000 items? maybe it read the item count before the first thread incremented it. So this flag allows you to say if you really do
	 * want that recomputation. If true, then the code will recompute zeta if the itemcount goes down. If false, the code will assume itemcount only goes up, and never recompute. 
	 */
	boolean allowitemcountdecrease=false;

	/******************************* Constructors **************************************/

	/**
	 * Create a zipfian generator for the specified number of items.
	 * @param _items The number of items in the distribution.
	 * @param scrambled Whether or not to scramble the distribution
	 * @param mirrored Whether or not to mirror the zipfian skew so items on both sides of the hottest tuple are also hot
	 * @param interval The time interval between changing skew
	 * @param shift The amount to shift the distribution each time
	 */
    public VaryingZipfianGenerator(long _items)
	{
	    this(0,_items-1);
	}

	/**
	 * Create a zipfian generator for items between min and max.
	 * @param _min The smallest integer to generate in the sequence.
	 * @param _max The largest integer to generate in the sequence.
	 * @param scrambled Whether or not to scramble the distribution
	 * @param mirrored Whether or not to mirror the zipfian skew so items on both sides of the hottest tuple are also hot
	 * @param interval The time interval between changing skew
	 * @param shift The amount to shift the distribution each time
	 */
	public VaryingZipfianGenerator(long _min, long _max)
	{
		this(_min,_max,ZIPFIAN_CONSTANT);
	}

	/**
	 * Create a zipfian generator for the specified number of items using the specified zipfian constant.
	 * 
	 * @param _items The number of items in the distribution.
	 * @param _zipfianconstant The zipfian constant to use.
	 * @param scrambled Whether or not to scramble the distribution
	 * @param mirrored Whether or not to mirror the zipfian skew so items on both sides of the hottest tuple are also hot
	 * @param interval The time interval between changing skew
	 * @param shift The amount to shift the distribution each time
	 */
	public VaryingZipfianGenerator(long _items, double _zipfianconstant)
	{
		this(0,_items-1,_zipfianconstant);
	}

	/**
	 * Create a zipfian generator for items between min and max (inclusive) for the specified zipfian constant.
	 * @param min The smallest integer to generate in the sequence.
	 * @param max The largest integer to generate in the sequence.
	 * @param _zipfianconstant The zipfian constant to use.
	 * @param scrambled Whether or not to scramble the distribution
	 * @param mirrored Whether or not to mirror the zipfian skew so items on both sides of the hottest tuple are also hot
	 * @param interval The time interval between changing skew
	 * @param shift The amount to shift the distribution each time
	 */
	public VaryingZipfianGenerator(long min, long max, double _zipfianconstant)
	{
		this(min,max,_zipfianconstant,zetastatic(max-min+1,_zipfianconstant));
	}
	
	/**
	 * Create a zipfian generator for items between min and max (inclusive) for the specified zipfian constant, using the precomputed value of zeta.
	 * 
	 * @param min The smallest integer to generate in the sequence.
	 * @param max The largest integer to generate in the sequence.
	 * @param _zipfianconstant The zipfian constant to use.
	 * @param _zetan The precomputed zeta constant.
	 * @param scrambled Whether or not to scramble the distribution
	 * @param mirrored Whether or not to mirror the zipfian skew so items on both sides of the hottest tuple are also hot
	 * @param interval The time interval between changing skew
	 * @param shift The amount to shift the distribution each time
	 */
	public VaryingZipfianGenerator(long min, long max, double _zipfianconstant, double _zetan)
	{
	    this.min=min;
	    this.max=max;
		this.lastTime = System.currentTimeMillis();
		this.totalShift = 0L;
		items=max-min+1;
		base=min;
		zipfianconstant=_zipfianconstant;

		theta=zipfianconstant;

		zeta2theta=zeta(2,theta);

		
		alpha=1.0/(1.0-theta);
		//zetan=zeta(items,theta);
		zetan=_zetan;
		countforzeta=items;
		if(theta >= 1) {
			generateItemMap();
		}
		else {
			eta=(1-Math.pow(2.0/items,1-theta))/(1-zeta2theta/zetan);
		}
		
		nextInt();
	}
	
	/**************************************************************************/
	
	/**********************************Getters/Setters****************************************/
	
	public boolean getScrambled() {
		return scrambled;
	}
    
    public boolean getMirrored() {
    	return mirrored;
    }
    
    public long getShift() {
    	return shift;
    }
    
    public boolean getRandomShift() {
    	return randomShift;
    }
    
    public long getInterval() {
    	return interval;
    }
    
    public int getNumHotSpots() {
    	return numHotSpots;
    }
    
    public ArrayList<Long> getHotSpots() {
    	return hotSpots;
    }
	
    public double getPercentAccessHotSpots() {
    	return percentAccessHotSpots;
    }

    public boolean getRandomHotSpots() {
        return randomHotSpots;
    }
    
    public void setScrambled(boolean scrambled) {
		this.scrambled = scrambled;
	}
    
    public void setMirrored(boolean mirrored) {
    	this.mirrored = mirrored;
    }
    
    public void setShift(long shift) {
    	this.shift = shift;
    }
    
    public void setRandomShift(boolean randomShift) {
    	this.randomShift = randomShift;
    }
    
    public void setInterval(long interval) {
    	this.interval = interval;
    }
    
    public void setNumHotSpots(int numHotSpots) {
    	this.numHotSpots = numHotSpots;
    	HashSet<Long> hotSpotsSet = new HashSet<Long>(numHotSpots);
    	long item = 0;
	if(randomHotSpots) {
    	  while(hotSpotsSet.size() < numHotSpots) {
    		Utils.random().setSeed(item);
    		item = Utils.random().nextLong() % items + min;
		if (item < min) item += items;
    		if(!hotSpotsSet.add(item)) {
		    item += 1;
		}
    	  }
	} else {
    	  for(int i = 0; i < numHotSpots; ++i) {
	      hotSpotsSet.add(i + min);
	  }
	}
    	hotSpots.clear();
    	hotSpots.addAll(hotSpotsSet);
    }
	
    public void setPercentAccessHotSpots(double percentAccessHotSpots) {
    	this.percentAccessHotSpots = percentAccessHotSpots;
    }

    public void setRandomHotSpots(boolean randomHotSpots) {
        this.randomHotSpots = randomHotSpots;
    }
	
	/**************************************************************************/
	
	/**
	 * Compute the zeta constant needed for the distribution. Do this from scratch for a distribution with n items, using the 
	 * zipfian constant theta. Remember the value of n, so if we change the itemcount, we can recompute zeta.
	 * 
	 * @param n The number of items to compute zeta over.
	 * @param theta The zipfian constant.
	 */
	double zeta(long n, double theta)
	{
		countforzeta=n;
		return zetastatic(n,theta);
	}
	
	/**
	 * Compute the zeta constant needed for the distribution. Do this from scratch for a distribution with n items, using the 
	 * zipfian constant theta. This is a static version of the function which will not remember n.
	 * @param n The number of items to compute zeta over.
	 * @param theta The zipfian constant.
	 */
	static double zetastatic(long n, double theta)
	{
		return zetastatic(0,n,theta,0);
	}
	
	/**
	 * Compute the zeta constant needed for the distribution. Do this incrementally for a distribution that
	 * has n items now but used to have st items. Use the zipfian constant theta. Remember the new value of 
	 * n so that if we change the itemcount, we'll know to recompute zeta.
	 * 
	 * @param st The number of items used to compute the last initialsum
	 * @param n The number of items to compute zeta over.
	 * @param theta The zipfian constant.
     * @param initialsum The value of zeta we are computing incrementally from.
	 */
	double zeta(long st, long n, double theta, double initialsum)
	{
		countforzeta=n;
		return zetastatic(st,n,theta,initialsum);
	}
	
	/**
	 * Compute the zeta constant needed for the distribution. Do this incrementally for a distribution that
	 * has n items now but used to have st items. Use the zipfian constant theta. Remember the new value of 
	 * n so that if we change the itemcount, we'll know to recompute zeta. 
	 * @param st The number of items used to compute the last initialsum
	 * @param n The number of items to compute zeta over.
	 * @param theta The zipfian constant.
     * @param initialsum The value of zeta we are computing incrementally from.
	 */
	static double zetastatic(long st, long n, double theta, double initialsum)
	{
		double sum=initialsum;
		for (long i=st; i<n; i++)
		{

			sum+=1/(Math.pow(i+1,theta));
		}
		
		//System.out.println("countforzeta="+countforzeta);
		
		return sum;
	}
	
	void generateItemMap() {
		itemMap = new TreeMap<Double, Long>();
		
		Long itemId = 0L;
		Double minRandVal = 0.0;
		while(minRandVal < 0.95) {
			itemMap.put(minRandVal, itemId);
			itemId++;
			minRandVal += (1.0/Math.pow(itemId, theta))/zetan;
		}
		//System.out.println("Size of item map: " + itemMap.size());

	}

	/****************************************************************************************/
	
	/** 
	 * Generate the next item. this distribution will be skewed toward lower integers; e.g. 0 will
	 * be the most popular, 1 the next most popular, etc.
	 * @param itemcount The number of items in the distribution.
	 * @return The next item in the sequence.
	 */
	public int nextInt(int itemcount)
	{
		return (int)nextLong(itemcount);
	}
	
	/** 
	 * Generate the next item. this distribution will be skewed toward lower integers; e.g. 0 will
	 * be the most popular, 1 the next most popular, etc.
	 * @param r1 - the random double to use for zipfian skew
	 * @param r2 - the random double to use to determine whether to assign a hot spot
	 * @param r3 - the random int to use to select the hot spot
	 * @return The next item in the sequence.
	 */
	@Override
	public int nextInt(double r1, double r2, int r3)
	{
		return (int)nextLong(items, r1, r2, r3);
	}

	/**
	 * Generate the next item as a long.
	 * 
	 * @param itemcount The number of items in the distribution.
	 * @return The next item in the sequence.
	 */
	public long nextLong(long itemcount) {
		return nextLong(itemcount, Utils.random().nextDouble(), Utils.random().nextDouble(), 
				(hotSpots.size() > 0 ? Utils.random().nextInt(hotSpots.size()) : 0));
	}
	
	
	/**
	 * Generate the next item as a long.
	 * 
	 * @param itemcount The number of items in the distribution.
	 * @param r1 - the random double to use for zipfian skew
	 * @param r2 - the random double to use to determine whether to assign a hot spot
	 * @param r3 - the random int to use to select the hot spot
	 * @return The next item in the sequence.
	 */
	public long nextLong(long itemcount, double r1, double r2, int r3)
	{
		//from "Quickly Generating Billion-Record Synthetic Databases", Jim Gray et al, SIGMOD 1994

		if (itemcount!=countforzeta)
		{

			//have to recompute zetan and eta, since they depend on itemcount
			synchronized(this)
			{
				if (itemcount>countforzeta)
				{
					//System.err.println("WARNING: Incrementally recomputing Zipfian distribtion. (itemcount="+itemcount+" countforzeta="+countforzeta+")");
					
					//we have added more items. can compute zetan incrementally, which is cheaper
					zetan=zeta(countforzeta,itemcount,theta,zetan);
					if(theta >= 1) {
						generateItemMap();
					}
					else {
						eta=(1-Math.pow(2.0/items,1-theta))/(1-zeta2theta/zetan);
					}
				}
				else if ( (itemcount<countforzeta) && (allowitemcountdecrease) )
				{
					//have to start over with zetan
					//note : for large itemsets, this is very slow. so don't do it!

					//TODO: can also have a negative incremental computation, e.g. if you decrease the number of items, then just subtract
					//the zeta sequence terms for the items that went away. This would be faster than recomputing from scratch when the number of items
					//decreases
					
					System.err.println("WARNING: Recomputing Zipfian distribtion. This is slow and should be avoided. (itemcount="+itemcount+" countforzeta="+countforzeta+")");
					
					zetan=zeta(itemcount,theta);
					if(theta >= 1) {
						generateItemMap();
					}
					else {
						eta=(1-Math.pow(2.0/items,1-theta))/(1-zeta2theta/zetan);
					}
				}
			}
		}

		double u=r1;
		double uz=u*zetan;
		long ret;

		if(r2 < this.percentAccessHotSpots) {
			int index = r3;
			ret = hotSpots.get(index);
		}
		else if(theta >= 1) {
			if(u < 0.95) {
				ret = itemMap.floorEntry(u).getValue();
			}
			else { // the last 5% are distributed uniformly
				Long maxItem = itemMap.lastEntry().getValue();
				ret = Utils.random().nextInt() % (items - maxItem) + maxItem;
				if(ret < maxItem) ret += (items - maxItem);
			}
		}
		else {
			if (uz<1.0)
			{
			    ret = 0;
			}
			else if (uz<1.0+Math.pow(0.5,theta)) 
			{
			    ret = 1;
			}
			else {
			    ret=base+(long)((itemcount) * Math.pow(eta*u - eta + 1, alpha));
			}
		}

		if(this.interval != DEFAULT_INTERVAL && System.currentTimeMillis() - this.interval > this.lastTime) {
			this.lastTime = System.currentTimeMillis();
			if(this.randomShift) {
				Utils.random().setSeed(shift);
				this.totalShift = Utils.random().nextInt();
			}
			else {
			    this.totalShift = (totalShift + shift) % items;
			}
			System.out.println("Changing distribution. Adding total shift: " + totalShift);
		}

		if(mirrored && Utils.random().nextBoolean()) {
		    ret = min + (max - ret + totalShift - min) % items;
		}
		else {
		    ret = min + (ret + totalShift - min) % items;
		}

		if(scrambled) {
		    ret=min+Utils.FNVhash64(ret)%items;
		}
		if(ret < min) ret += items;
		setLastInt((int)ret);
		return ret;
	}

	/**
	 * Return the next value, skewed by the Zipfian distribution. The 0th item will be the most popular, followed by the 1st, followed
	 * by the 2nd, etc. (Or, if min != 0, the min-th item is the most popular, the min+1th item the next most popular, etc.) If you want the
	 * popular items scattered throughout the item space, set scrambled=true.
	 */
	@Override
	public int nextInt() 
	{
		return (int)nextLong(items);
	}

	/**
	 * Return the next value, skewed by the Zipfian distribution. The 0th item will be the most popular, followed by the 1st, followed
	 * by the 2nd, etc. (Or, if min != 0, the min-th item is the most popular, the min+1th item the next most popular, etc.) If you want the
	 * popular items scattered throughout the item space, set scrambled=true.
	 */
	public long nextLong()
	{
		return nextLong(items);
	}
	
	public static void main(String[] args)
	{
		new VaryingZipfianGenerator(ITEM_COUNT);
	}

	/**
	 * @todo Implement VaryingZipfianGenerator.mean()
	 */
	@Override
	public double mean() {
	    if(scrambled) {
		// since the values are scrambled (hopefully uniformly), the mean is simply the middle of the range.
		return ((double)(min + max))/2.0;
	    }
	    else {
		throw new UnsupportedOperationException("@todo implement VaryingZipfianGenerator.mean()");
	    }
	}
}
