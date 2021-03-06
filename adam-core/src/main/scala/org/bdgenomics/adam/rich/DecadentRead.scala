/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.adam.rich

import org.bdgenomics.formats.avro.ADAMRecord
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rich.RichADAMRecord._
import org.bdgenomics.adam.util.MdTag
import org.bdgenomics.adam.util.QualityScore
import org.apache.spark.rdd.RDD
import org.apache.spark.Logging
import scala.Some
import org.bdgenomics.adam.models.ReferencePosition

object DecadentRead {
  type Residue = DecadentRead#Residue

  // Constructors
  def apply(record: ADAMRecord): DecadentRead = DecadentRead(RichADAMRecord(record))

  def apply(rich: RichADAMRecord): DecadentRead = {
    try {
      new DecadentRead(rich)
    } catch {
      case exc: Exception =>
        val msg = "Error \"%s\" while constructing DecadentRead from ADAMRecord(%s)".format(exc.getMessage, rich.record)
        throw new IllegalArgumentException(msg, exc)
    }
  }

  /**
   * cloy (verb)
   *   1. To fill to loathing; to surfeit.
   *   2. To clog, to glut, or satisfy, as the appetite; to satiate.
   *   3. To fill up or choke up; to stop up.
   */
  def cloy(rdd: RDD[ADAMRecord]): RDD[DecadentRead] = rdd.map(DecadentRead.apply)

  // The inevitable counterpart of the above.
  implicit def decay(rdd: RDD[DecadentRead]): RDD[ADAMRecord] = rdd.map(_.record)
}

class DecadentRead(val record: RichADAMRecord) extends Logging {
  // Can't be a primary alignment unless it has been aligned
  require(!record.getPrimaryAlignment || record.getReadMapped, "Unaligned read can't be a primary alignment")

  // Should have quality scores for all residues
  require(record.getSequence.length == record.qualityScores.length, "sequence and qualityScores must be same length")

  // MapQ should be valid
  require(record.getMapq == null || (record.getMapq >= 0 && record.getMapq <= 93), "MapQ must be in [0, 255]")

  // Alignment must be valid
  require(!record.getReadMapped || record.getStart >= 0, "Invalid alignment start index")

  // Sanity check on referencePositions
  require(record.referencePositions.length == record.getSequence.length)

  /**
   * In biochemistry and molecular biology, a "residue" refers to a specific
   * monomer within a polymeric chain, such as DNA.
   */
  class Residue private[DecadentRead] (val offset: Int) {
    def read = DecadentRead.this

    /**
     * Nucleotide at this offset.
     *
     * TODO: Return values of meaningful type, e.g. `DNABase'.
     */
    def base: Char = read.baseSequence(offset)

    def quality = QualityScore(record.qualityScores(offset))

    def isRegularBase: Boolean = base match {
      case 'A' => true
      case 'C' => true
      case 'T' => true
      case 'G' => true
      case 'N' => false
      case unk => throw new IllegalArgumentException("Encountered unexpected base '%s'".format(unk))
    }

    def isMismatch(includeInsertions: Boolean = true): Boolean =
      assumingAligned(record.isMismatchAtReadOffset(offset).getOrElse(includeInsertions))

    def isSNP: Boolean = isMismatch(false)

    def isInsertion: Boolean =
      assumingAligned(record.isMismatchAtReadOffset(offset).isEmpty)

    def referencePositionOption: Option[ReferencePosition] =
      assumingAligned(
        record.readOffsetToReferencePosition(offset))

    def referenceSequenceContext: Option[ReferenceSequenceContext] =
      assumingAligned(record.readOffsetToReferenceSequenceContext(offset))

    def referencePosition: ReferencePosition =
      referencePositionOption.getOrElse(
        throw new IllegalArgumentException("Residue has no reference location (may be an insertion)"))
  }

  lazy val readGroup: String = record.getRecordGroupName.toString

  private lazy val baseSequence: String = record.getSequence.toString

  lazy val residues: IndexedSeq[Residue] = Range(0, baseSequence.length).map(new Residue(_))

  def name: String = record.getReadName

  def isAligned: Boolean = record.getReadMapped

  def alignmentQuality: Option[QualityScore] = assumingAligned {
    if (record.getMapq == null || record.getMapq == 255) {
      None
    } else {
      Some(QualityScore(record.getMapq))
    }
  }

  def ensureAligned: Boolean =
    isAligned || (throw new IllegalArgumentException("Read has not been aligned to a reference"))

  def isPrimaryAlignment: Boolean = isAligned && record.getPrimaryAlignment

  def isDuplicate: Boolean = record.getDuplicateRead

  def isPaired: Boolean = record.getReadPaired

  def isFirstOfPair: Boolean = isPaired && !record.getSecondOfPair

  def isSecondOfPair: Boolean = isPaired && record.getSecondOfPair

  def isNegativeRead: Boolean = record.getReadNegativeStrand

  // Is this the most representative record for this read?
  def isCanonicalRecord: Boolean = isPrimaryAlignment && !isDuplicate

  def passedQualityChecks: Boolean = !record.getFailedVendorQualityChecks

  def mismatchesOption: Option[MdTag] = record.mdTag

  def mismatches: MdTag =
    mismatchesOption.getOrElse(throw new IllegalArgumentException("Read has no MD tag"))

  private def assumingAligned[T](func: => T): T = {
    ensureAligned
    func
  }
}
