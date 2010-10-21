/*
 * OpenBench LogicSniffer / SUMP project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110, USA
 *
 * Copyright (C) 2006-2010 Michael Poppitz, www.sump.org
 * Copyright (C) 2010 J.W. Janssen, www.lxtreme.nl
 */
package nl.lxtreme.ols.tool.spi;


import java.util.logging.*;

import nl.lxtreme.ols.api.data.*;
import nl.lxtreme.ols.api.tools.*;
import nl.lxtreme.ols.tool.base.*;
import nl.lxtreme.ols.util.*;
import nl.lxtreme.ols.util.NumberUtils.BitOrder;
import nl.lxtreme.ols.util.analysis.*;


/**
 * @author jajans
 */
public class SPIAnalyserWorker extends BaseAsyncToolWorker<SPIDataSet>
{
  // CONSTANTS

  private static final Logger LOG = Logger.getLogger( SPIAnalyserWorker.class.getName() );

  public static final String PROPERTY_AUTO_DETECT_MODE = "AutoDetectSPIMode";

  // VARIABLES

  private int csIdx;
  private int sckIdx;
  private SPIMode mode;
  private int bitCount;
  private BitOrder bitOrder;
  private boolean reportCS;
  private boolean honourCS;
  private int mosiIdx;
  private int misoIdx;

  // CONSTRUCTORS

  /**
   * @param aData
   */
  public SPIAnalyserWorker( final DataContainer aData, final ToolContext aContext )
  {
    super( aData, aContext );
  }

  // METHODS

  /**
   * Sets the number of bits an SPI datagram should consist of.
   * 
   * @param aBitCount
   *          the number of bits in a SPI datagram, >= 8.
   */
  public void setBitCount( final int aBitCount )
  {
    this.bitCount = aBitCount;
  }

  /**
   * Sets the chip-select channel index.
   * 
   * @param aCsMask
   *          the index of the chip-select channel.
   */
  public void setCSIndex( final int aCsIndex )
  {
    this.csIdx = aCsIndex;
  }

  /**
   * Sets whether or not chip-select should be honoured in the analysis.
   * 
   * @param aHonourCS
   *          <code>true</code> to only decode data when the chip-select line is
   *          low, <code>false</code> to decode all data.
   */
  public void setHonourCS( final boolean aHonourCS )
  {
    this.honourCS = aHonourCS;
  }

  /**
   * Sets the "master-in slave-out" channel index.
   * 
   * @param aMisoMask
   *          the index of the "master-in slave-out" channel.
   */
  public void setMisoIndex( final int aMisoIndex )
  {
    this.misoIdx = aMisoIndex;
  }

  /**
   * Sets which SPI mode should be used for the analysis process.
   * 
   * @param aMode
   *          the SPI mode to set, cannot be <code>null</code>.
   */
  public void setMode( final SPIMode aMode )
  {
    this.mode = aMode;
  }

  /**
   * Sets the "master-out slave-in" channel index.
   * 
   * @param aMosiMask
   *          the index of the "master-out slave-in" channel.
   */
  public void setMosiIndex( final int aMosiIndex )
  {
    this.mosiIdx = aMosiIndex;
  }

  /**
   * Sets the order in which bits in a SPI datagram are transmitted.
   * 
   * @param aOrder
   *          the bit order to use, cannot be <code>null</code>.
   */
  public void setOrder( final BitOrder aOrder )
  {
    this.bitOrder = aOrder;
  }

  /**
   * Sets whether or not chip-select events should be reported.
   * 
   * @param aReportCS
   *          <code>true</code> to include chip-select events in the analysis
   *          result, <code>false</code> to exclude them.
   */
  public void setReportCS( final boolean aReportCS )
  {
    this.reportCS = aReportCS;

  }

  /**
   * Sets the serial-clock channel index.
   * 
   * @param aSckIndex
   *          the index of the "serial-clock" channel.
   */
  public void setSCKIndex( final int aSckIndex )
  {
    this.sckIdx = aSckIndex;
  }

  /**
   * This is the SPI protocol decoder core The decoder scans for a decode start
   * event like CS high to low edge or the trigger of the captured data. After
   * this the decoder starts to decode the data by the selected mode, number of
   * bits and bit order. The decoded data are put to a JTable object directly.
   * 
   * @see javax.swing.SwingWorker#doInBackground()
   */
  @Override
  protected SPIDataSet doInBackground() throws Exception
  {
    if ( LOG.isLoggable( Level.FINE ) )
    {
      LOG.fine( "csmask   = 0x" + Integer.toHexString( 1 << this.csIdx ) );
      LOG.fine( "sckmask  = 0x" + Integer.toHexString( 1 << this.sckIdx ) );
      LOG.fine( "misomask = 0x" + Integer.toHexString( 1 << this.misoIdx ) );
      LOG.fine( "mosimask = 0x" + Integer.toHexString( 1 << this.mosiIdx ) );
    }

    final int startOfDecode = getContext().getStartSampleIndex();
    final int endOfDecode = getContext().getEndSampleIndex();
    final boolean slaveSelected = slaveSelected( startOfDecode, endOfDecode );

    if ( ( this.honourCS && !slaveSelected ) || ( startOfDecode >= endOfDecode ) )
    {
      // no CS edge found, look for trigger
      LOG.log( Level.WARNING, "No CS start-condition found! Analysis aborted..." );
      throw new IllegalStateException( "No CS start-condition found!" );
    }

    // Initialize the channel labels + clear any existing annotations...
    prepareResults();

    if ( this.mode == null )
    {
      LOG.log( Level.INFO, "Detecting which SPI mode is most probably used..." );
      this.mode = detectSPIMode( startOfDecode, endOfDecode );
    }

    // Notify any listeners of the detected mode...
    firePropertyChange( PROPERTY_AUTO_DETECT_MODE, null, this.mode );

    final SPIDataSet decodedData = new SPIDataSet( startOfDecode, endOfDecode, this );
    if ( slaveSelected )
    {
      // now the trigger is in b, add trigger event to table
      reportCsLow( decodedData, startOfDecode );
    }

    // Perform the actual decoding of the data line(s)...
    clockDataOnEdge( decodedData, this.mode );

    return decodedData;
  }

  /**
   * Decodes the SPI-data on a given clock edge.
   * 
   * @param aDataSet
   *          the decoded data to fill;
   * @param aMode
   *          the SPI mode defining the edges on which data can be sampled and
   *          on which edges data can change.
   */
  private void clockDataOnEdge( final SPIDataSet aDataSet, final SPIMode aMode )
  {
    final int[] values = getValues();

    final int startOfDecode = aDataSet.getStartOfDecode();
    final int endOfDecode = aDataSet.getEndOfDecode();

    final Edge sampleEdge = aMode.getSampleEdge();
    final Edge dataChangeEdge = aMode.getDataChangeEdge();

    final int misoMask = 1 << this.misoIdx;
    final int mosiMask = 1 << this.mosiIdx;
    final int sckMask = 1 << this.sckIdx;
    final int csMask = 1 << this.csIdx;

    // scanning for falling/rising clk edges
    int oldSckValue = ( values[startOfDecode] & sckMask );
    int oldCsValue = ( values[startOfDecode] & csMask );

    // We've already found the
    boolean slaveSelected = true;
    boolean dataEdgeSeen = false;
    int lastIdx = startOfDecode;
    int bitIdx = this.bitCount;

    int misovalue = 0;
    int mosivalue = 0;

    final double length = endOfDecode - startOfDecode;
    for ( int idx = startOfDecode + 1; idx < endOfDecode; idx++ )
    {
      /* CLK edge detection */
      final int sckValue = values[idx] & sckMask;
      /* CS edge detection */
      final int csValue = values[idx] & csMask;

      final Edge slaveSelectEdge = Edge.toEdge( oldCsValue, csValue );
      oldCsValue = csValue;

      if ( slaveSelectEdge.isFalling() )
      {
        reportCsLow( aDataSet, idx );

        slaveSelected = true;
        dataEdgeSeen = false;
      }
      else if ( slaveSelectEdge.isRising() )
      {
        reportCsHigh( aDataSet, idx );

        slaveSelected = false;
      }

      if ( this.honourCS && !slaveSelected )
      {
        // We should honour the slave-select, but the slave isn't
        // currently selected...
        continue;
      }

      final Edge clockEdge = Edge.toEdge( oldSckValue, sckValue );
      oldSckValue = sckValue;

      // In case the clock is phase-shifted with respect to the data line,
      // we should wait until the first inverse edge is seen. To put it
      // otherwise, we should simply ignore the first seen clock edge and
      // wait for the second one...
      if ( dataChangeEdge == clockEdge )
      {
        dataEdgeSeen = true;
      }
      // Keep track where we saw the first clocked bit of a byte-value...
      if ( dataEdgeSeen )
      {
        if ( bitIdx == this.bitCount )
        {
          lastIdx = idx - 1;
        }
      }

      final boolean sampleEdgeSeen = dataEdgeSeen && ( sampleEdge == clockEdge );
      if ( sampleEdgeSeen )
      {
        // sample MiSo here; always MSB first, perform conversion later on...
        if ( ( values[idx] & misoMask ) == misoMask )
        {
          misovalue |= ( 1 << bitIdx );
        }
        // sample MoSi here; always MSB first, perform conversion later on...
        if ( ( values[idx] & mosiMask ) == mosiMask )
        {
          mosivalue |= ( 1 << bitIdx );
        }

        if ( bitIdx > 0 )
        {
          bitIdx--;
        }
        else if ( bitIdx == 0 )
        {
          // Full datagram decoded...
          reportData( aDataSet, lastIdx, idx, mosivalue, misovalue );

          bitIdx = this.bitCount;
          misovalue = 0;
          mosivalue = 0;
        }
      }

      setProgress( ( int )( ( idx - startOfDecode ) * 100.0 / length ) );
    }
  }

  /**
   * Tries the detect what the clock polarity of the contained data values is.
   * Based on this we can make a "educated" guess what SPI mode should be used
   * for the decoding of the remainder of data.
   * <p>
   * Currently, there is no way I can think of how the CPHA value can be
   * determined from the data. Hence, we can only determine the clock polarity
   * (CPOL), which also provides a good idea on what mode the SPI-data is.
   * </p>
   * 
   * @param aStartIndex
   *          the starting sample index to use;
   * @param aEndIndex
   *          the ending sample index to use.
   * @return the presumed SPI mode, either mode 1 or 3.
   */
  private SPIMode detectSPIMode( final int aStartIndex, final int aEndIndex )
  {
    final Frequency<Integer> valueStats = new Frequency<Integer>();

    final int[] values = getValues();
    final int sckMask = 1 << this.sckIdx;

    // Determine the value of the clock line of each sample; the value that
    // occurs the most is probably the default polarity...
    for ( int i = aStartIndex; i < aEndIndex; i++ )
    {
      final int newValue = ( values[i] & sckMask ) >> this.sckIdx;
      valueStats.addValue( Integer.valueOf( newValue ) );
    }

    SPIMode result;

    // If the clock line's most occurring value is one, then
    // we're fairly sure that CPOL == 1...
    if ( valueStats.getHighestRanked() == 1 )
    {
      LOG.log( Level.INFO, "SPI mode is probably mode 2 or 3 (CPOL == 1). Assuming mode 3 ..." );
      result = SPIMode.MODE_3;
    }
    else
    {
      LOG.log( Level.INFO, "SPI mode is probably mode 0 or 1 (CPOL == 0). Assuming mode 1 ..." );
      result = SPIMode.MODE_1;
    }

    return result;
  }

  /**
   * Determines the channel labels that are used in the annotations and reports
   * and clears any existing annotations on the decoded channels.
   */
  private void prepareResults()
  {
    updateChannelLabel( this.mosiIdx, SPIDataSet.SPI_MOSI );
    updateChannelLabel( this.misoIdx, SPIDataSet.SPI_MISO );
    updateChannelLabel( this.sckIdx, SPIDataSet.SPI_SCK );
    updateChannelLabel( this.csIdx, SPIDataSet.SPI_CS );

    // clear any existing annotations
    clearChannelAnnotations( this.mosiIdx );
    clearChannelAnnotations( this.misoIdx );
    clearChannelAnnotations( this.sckIdx );
    clearChannelAnnotations( this.csIdx );
  }

  /**
   * Reports a slave-select low->high transition, effectively causing the slave
   * to be no longer selected.
   * 
   * @param aDecodedData
   *          the data set to add the event to;
   * @param aIndex
   *          the sample index on which the event occurred.
   */
  private void reportCsHigh( final SPIDataSet aDecodedData, final int aIndex )
  {
    if ( this.reportCS )
    {
      aDecodedData.reportCSHigh( this.csIdx, aIndex );
    }
  }

  /**
   * Reports a slave-select high->low transition, effectively causing the slave
   * to be selected.
   * 
   * @param aDecodedData
   *          the data set to add the event to;
   * @param aIndex
   *          the sample index on which the event occurred.
   */
  private void reportCsLow( final SPIDataSet aDecodedData, final int aIndex )
  {
    if ( this.reportCS )
    {
      aDecodedData.reportCSLow( this.csIdx, aIndex );
    }
  }

  /**
   * Reports a set of data-bytes (both MISO and MOSI).
   * 
   * @param aDecodedData
   *          the data set to add the data event(s) to;
   * @param aStartIdx
   *          the starting sample index on which the data started;
   * @param aEndIdx
   *          the ending sample index on which the data ended;
   * @param aMosiValue
   *          the MOSI data value;
   * @param aMisoValue
   *          the MISO data value.
   */
  private void reportData( final SPIDataSet aDecodedData, final int aStartIdx, final int aEndIdx, final int aMosiValue,
      final int aMisoValue )
  {
    // Perform bit-order conversion on the full byte...
    final int mosivalue = NumberUtils.convertByteOrder( aMosiValue, this.bitOrder );
    final int misovalue = NumberUtils.convertByteOrder( aMisoValue, this.bitOrder );

    addChannelAnnotation( this.mosiIdx, aStartIdx, aEndIdx, String.format( "0x%X (%c)", mosivalue, mosivalue ) );
    aDecodedData.reportMosiData( this.mosiIdx, aStartIdx, aEndIdx, mosivalue );

    addChannelAnnotation( this.misoIdx, aStartIdx, aEndIdx, String.format( "0x%X (%c)", misovalue, misovalue ) );
    aDecodedData.reportMisoData( this.misoIdx, aStartIdx, aEndIdx, misovalue );
  }

  /**
   * @param aSampleIndex
   * @param aI
   * @return
   */
  private int searchSlaveSelected( final int aStartIndex, final int aEndIndex )
  {
    final int[] values = getValues();

    final int csMask = 1 << this.csIdx;

    /*
     * For analyze scan the CS line for a falling edge. If no edge could be
     * found, the position of the trigger is used for start of analysis. If no
     * trigger and no edge is found the analysis fails.
     */
    int oldCsValue = values[aStartIndex] & csMask;
    for ( int i = aStartIndex + 1; i < aEndIndex; i++ )
    {
      final int csValue = values[i] & csMask;
      if ( oldCsValue > csValue )
      {
        // found first falling edge; start decoding from here...
        if ( LOG.isLoggable( Level.FINE ) )
        {
          LOG.fine( "CS found at " + i );
        }

        return i;
      }
      oldCsValue = csValue;
    }

    return -1;
  }

  /**
   * Tries to find whether the SPI-slave is actually selected due to CS going
   * low (defaults to high).
   * 
   * @param aStartIndex
   *          the starting sample index to use;
   * @param aEndIndex
   *          the ending sample index to use.
   * @return <code>true</code> if the slave is actually selected,
   *         <code>false</code> otherwise.
   */
  private boolean slaveSelected( final int aStartOfDecode, final int aEndOfDecode )
  {
    boolean slaveSelected = false;

    // Search for a CS-low backwards from the first cursor...
    slaveSelected = searchSlaveSelected( 0, aStartOfDecode ) >= 0;
    if ( !slaveSelected )
    {
      // Search for a CS-low forwards from the first cursor...
      slaveSelected = searchSlaveSelected( aStartOfDecode, aEndOfDecode ) >= 0;
    }
    return slaveSelected;
  }
}
