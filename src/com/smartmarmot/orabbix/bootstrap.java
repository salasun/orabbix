/*
 * Copyright (C) 2010 Andrea Dalle Vacche.
 * @author Andrea Dalle Vacche
 *
 * This file is part of DBforBIX.
 *
 * Orabbix is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Orabbix is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Orabbix. If not, see <http://www.gnu.org/licenses/>.
 */

package com.smartmarmot.orabbix;

import com.smartmarmot.common.SmartLogger;
import org.apache.log4j.Level;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;

public class bootstrap {


    private static Orabbixmon runner;

    private static void printUsage() {
        System.out.println(Constants.BANNER);
    }

    /**
     * @param args
     */
    public static void main(final String[] args) {
        try {
            if (args.length > 2) {
                printUsage();
                System.exit(0);
            }

            String cmd = args[0];
            String configFile = args[1];

            if (cmd.equalsIgnoreCase("start")) {
                runner = new Orabbixmon(configFile);
                new Thread(runner);
                runner.run();
            }
            else if (args[0].equalsIgnoreCase("stop")) {
                if (runner != null) {
                    runner.terminate();
                    System.err.println("Runner terminated");
                } else
                    System.err.println("No daemon running");
            }
            else {
                printUsage();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

    }


}