package com.act.biointerpretation.rsmiles

import act.server.MongoDB
import chemaxon.formats.MolFormatException
import chemaxon.struc.Molecule
import com.act.analysis.chemicals.molecules.{MoleculeFormat, MoleculeImporter}
import com.act.workflow.tool_manager.workflow.workflow_mixins.mongo.{ChemicalKeywords, MongoWorkflowUtilities}
import com.mongodb.DBObject
import org.apache.log4j.LogManager

import scala.collection.parallel.immutable.{ParMap, ParSeq}

object AbstractChemicals {
  val logger = LogManager.getLogger(getClass)

  def getAbstractChemicals(mongoDb: MongoDB): ParMap[Long, ChemicalInformation] = {
    logger.info("Finding abstract chemicals.")
    /*
      Mongo DB Query

      Query: All elements that contain "R" in their SMILES and "FAKE" in their InChI
     */
    var query = Mongo.createDbObject(ChemicalKeywords.SMILES, Mongo.defineMongoRegex("R"))
    query = Mongo.appendKeyToDbObject(query, ChemicalKeywords.INCHI, Mongo.defineMongoRegex("FAKE"))
    val filter = Mongo.createDbObject(ChemicalKeywords.SMILES, 1)
    val result: ParSeq[DBObject] = Mongo.mongoQueryChemicals(mongoDb)(query, filter, notimeout = true).toStream.par

    /*
       Convert from DB Object => Smarts and return that.
       Flatmap as Parse Db object returns None if an error occurs (Just filter out the junk)
    */
    val goodChemicalIds: ParMap[Long, ChemicalInformation] = result.flatMap(parseDbObjectForSmiles(_)).toMap

    logger.info(s"Finished finding abstract chemicals. Found ${goodChemicalIds.size}")

    goodChemicalIds
  }

  private def parseDbObjectForSmiles(ob: DBObject): Option[(Long, ChemicalInformation)] = {
    /*
      Type conversions from DB objects
     */
    val chemicalId: Long = ob.get(ChemicalKeywords.ID.toString).asInstanceOf[Long]
    val smiles: String = ob.get(ChemicalKeywords.SMILES.toString).asInstanceOf[String]

    // Replace R groups for C currently.
    val replacedSmarts = smiles.replaceAll("R[0-9]?", "C")

    /*
      Try to import the SMILES field as a Smarts representation of the molecule.
     */
    try {
      // Chemaxon technically uses smarts when we say Smiles, so we just make it explicit here.
      val mol = MoleculeImporter.importMolecule(replacedSmarts, MoleculeFormat.smarts)
      Option((chemicalId, new ChemicalInformation(chemicalId.toInt, mol, replacedSmarts)))
    } catch {
      case e: MolFormatException => None
    }
  }

  class ChemicalInformation(chemicalId: Int, molecule: Molecule, stringVersion: String) {
    def getChemicalId: Int = chemicalId

    def getMolecule: Molecule = molecule

    def getString: String = stringVersion
  }

  object Mongo extends MongoWorkflowUtilities {}

}
