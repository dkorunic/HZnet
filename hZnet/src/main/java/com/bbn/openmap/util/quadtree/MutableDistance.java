// **********************************************************************
// 
// <copyright>
// 
//  BBN Technologies
//  10 Moulton Street
//  Cambridge, MA 02138
//  (617) 873-8000
// 
//  Copyright (C) BBNT Solutions LLC. All rights reserved.
// 
// </copyright>
// **********************************************************************
// 
// $Source$
// $RCSfile$
// $Revision$
// $Date$
// $Author$
// 
// **********************************************************************

package com.bbn.openmap.util.quadtree;

/**
 * A *really* simple class used as a changable double.
 */
public class MutableDistance {
    public double value = 0;

    public MutableDistance(double distance) {
        value = distance;
    }
}