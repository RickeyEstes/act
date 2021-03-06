/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.lcms.db.io;

import com.act.lcms.MS1;
import com.act.utils.TSVParser;
import com.act.lcms.db.model.CuratedStandardMetlinIon;
import com.act.lcms.db.model.StandardIonResult;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.LocalDateTime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LoadStandardIonAnalysisTableIntoDB {
  public static final String OPTION_FILE_PATH = "i";
  public static final String OPTION_AUTHOR = "a";

  public static final String HELP_MESSAGE = StringUtils.join(new String[]{
      "This class is used to load a TSV file containing standard ion result data that may have been editted by a " +
      "scientist and correctly persist those edits to the standard ion table and the curated chemicals table. " +
      "One would run this class after making edits to the tsv file (usually present in the pipeline dir in github)."
  }, "");

  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();
  static {
    HELP_FORMATTER.setWidth(100);
  }

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_FILE_PATH)
        .argName("CSV file path")
        .desc("The file from which data is ingested into the DB")
        .hasArg().required()
    );
    add(Option.builder(OPTION_AUTHOR)
        .argName("commit author")
        .desc("The author of the commit")
        .hasArg().required()
    );
    // Everybody needs a little help from their friends sometime.
    add(Option.builder("h")
        .argName("help")
        .desc("Prints this help message")
        .longOpt("help")
    );
  }};

  static {
    // Add DB connection options.
    OPTION_BUILDERS.addAll(DB.DB_OPTION_BUILDERS);
  }

  public static void main(String[] args) throws Exception {
    Options opts = new Options();
    for (Option.Builder b : OPTION_BUILDERS) {
      opts.addOption(b.build());
    }

    CommandLine cl = null;

    try {
      CommandLineParser parser = new DefaultParser();
      cl = parser.parse(opts, args);
    } catch (ParseException e) {
      System.err.format("Argument parsing failed: %s\n", e.getMessage());
      HELP_FORMATTER.printHelp(LoadStandardIonAnalysisTableIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    if (cl.hasOption("help")) {
      HELP_FORMATTER.printHelp(LoadStandardIonAnalysisTableIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      return;
    }

    File inputFile = new File(cl.getOptionValue(OPTION_FILE_PATH));
    if (!inputFile.exists()) {
      System.err.format("Unable to find input file at %s\n", cl.getOptionValue(OPTION_FILE_PATH));
      new HelpFormatter().printHelp(LoadConstructAnalysisTableIntoDB.class.getCanonicalName(), opts, true);
      System.exit(1);
    }

    try (DB db = DB.openDBFromCLI(cl)) {
      db.getConn().setAutoCommit(false);

      TSVParser parser = new TSVParser();
      parser.parse(inputFile);

      for (Map<String, String> row : parser.getResults()) {
        Integer standardIonResultId = Integer.parseInt(
            row.get(ExportStandardIonResultsFromDB.STANDARD_ION_HEADER_FIELDS.STANDARD_ION_RESULT_ID.name()));

        String dbValueOfMetlinIon = ExportStandardIonResultsFromDB.NULL_VALUE;
        StandardIonResult ionResult = StandardIonResult.getInstance().getById(db, standardIonResultId);
        if (ionResult.getManualOverrideId() != null) {
          // There is an existing manual override ion in the DB
          CuratedStandardMetlinIon curatedChemical =
              CuratedStandardMetlinIon.getBestMetlinIon(db, ionResult.getManualOverrideId());
          dbValueOfMetlinIon = curatedChemical.getBestMetlinIon();
        }

        String manualPickOfMetlinIon = row.get(ExportStandardIonResultsFromDB.STANDARD_ION_HEADER_FIELDS.MANUAL_PICK.name());

        // If the manual metlin ion pick row is not NULL and it is not the same as the value stored in the DB, then
        // we need to add a new entry to the curated metlin ion table.
        if (!manualPickOfMetlinIon.equals(ExportStandardIonResultsFromDB.NULL_VALUE) &&
            !manualPickOfMetlinIon.equals(dbValueOfMetlinIon)) {
          System.out.format("Manual override has been found, so updating the DB\n");
          // A manual entry was created.
          if (!MS1.VALID_MS1_IONS.contains(manualPickOfMetlinIon)) {
            System.err.format("ERROR: found invalid chemical name: %s\n", manualPickOfMetlinIon);
            System.exit(-1);
          }

          String note = row.get(ExportStandardIonResultsFromDB.STANDARD_ION_HEADER_FIELDS.NOTE.name());
          CuratedStandardMetlinIon result = CuratedStandardMetlinIon.insertCuratedStandardMetlinIonIntoDB(
              db, LocalDateTime.now(CuratedStandardMetlinIon.utcDateTimeZone), cl.getOptionValue(OPTION_AUTHOR),
              manualPickOfMetlinIon, note, standardIonResultId);

          if (result == null) {
            System.err.format("WARNING: Could not insert curated entry to the curated metlin ion table\n",
                manualPickOfMetlinIon);
            System.exit(-1);
          } else {
            StandardIonResult getIonResult = StandardIonResult.getInstance().getById(db, standardIonResultId);
            getIonResult.setManualOverrideId(result.getId());
            if (!StandardIonResult.getInstance().update(db, getIonResult)) {
              System.err.format("WARNING: Could not insert manual override id to the standard ion table\n",
                  manualPickOfMetlinIon);
              System.exit(-1);
            } else {
              System.out.format("Successfully committed updates to the standard ion table and the curated metlin ion table\n");
            }
          }
        }
      }

      db.getConn().commit();
    }
  }
}
