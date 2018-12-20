package com.smartmarmot.orabbix;



/* This file is part of Zapcat.
 *
 * Zapcat is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * Zapcat is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Zapcat. If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.*;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.concurrent.BlockingQueue;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import org.apache.commons.codec.binary.Base64;

import com.smartmarmot.common.SmartLogger;
import com.smartmarmot.zabbix.ZabbixItem;

/**
 * A daemon thread that waits for and forwards data items to a Zabbix server.
 *
 * @author Kees Jan Koster Completely modified by
 * Andrea Dalle Vacche
 */
public final class Sender implements Runnable {
    private static final Logger log = Logger.getLogger("Orabbix");

    private final BlockingQueue<ZabbixItem> queue;

    private final Hashtable<String, Integer> zabbixServers;


    private final String head;

    private final String host;

    private static final String middle = "</key><data>";

    private static final String tail = "</data></req>";

    private final byte[] response = new byte[1024];

    private boolean stopping = false;

    private static final int retryNumber = 10;

    private static final int TIMEOUT = 30 * 1000;

    /**
     * Create a new background sender.
     *
     * @param queue         The queue to get data items from.
     * @param zabbixServers The name or IP of the machine to send the data to.
     *                      //     * @param zabbixPort   The port number on that machine.
     * @param host          The host name, as defined in the host definition in Zabbix.
     */
    public Sender(final BlockingQueue<ZabbixItem> queue,
                  Hashtable<String, Integer> zabbixServers,
                  final String host) {
      /*  super("Zabbix-sender");
        setDaemon(true);
*/
        this.queue = queue;
        this.zabbixServers = zabbixServers;
        this.host = host;
        this.head = "<req><host>" + base64Encode(host) + "</host><key>";
    }

    /**
     * Indicate that we are about to stop.
     */
    public void stopping() {
        stopping = true;
        /*interrupt();*/
    }

    /**
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        try {
            final ZabbixItem item = queue.take();
            int retryCount = 0;
            trysend1:
            while (retryCount <= retryNumber) {
                try {
                    send(item.getKey(), item.getValue());
                    break;
                } catch (Exception e) {
                    SmartLogger.logThis(Level.WARN,
                            "Warning while sending item " + item.getKey()
                                    + " value " + item.getValue() + " on host "
                                    + host + " retry number " + retryCount
                                    + " error:" + e);
                    Thread.sleep(1000);
                    retryCount++;
                    if (retryCount == retryNumber) {
                        SmartLogger.logThis(Level.WARN,
                                "Error i didn't sent item " + item.getKey()
                                        + " on Zabbix server " + " on host "
                                        + host + " tried " + retryCount
                                        + " times");
                    }
                    continue trysend1;
                }
            }
        } catch (InterruptedException e) {
            if (!stopping) {
                log.warn("ignoring exception", e);
            }

        } catch (Exception e) {
            log.warn("ignoring exception", e);
        }


        // drain the queue
        while (queue.size() > 0) {
            final ZabbixItem item = queue.remove();
            int retryCount = 0;
            trysend2:
            while (retryCount <= retryNumber) {
                try {
                    if (item.getKey().equals("tablespace")) {
                        tablespace(item.getValue());
                    }
                    else if(item.getKey().startsWith("global_")){
                        instance(item.getKey(), item.getValue());
                    }
                    else {
                        send(item.getKey(), item.getValue());
                    }
                    break;
                } catch (Exception e) {
                    SmartLogger.logThis(Level.WARN,
                            "Warning while sending item " + item.getKey()
                                    + " on host " + host + " retry number "
                                    + retryCount + " error:" + e);
                    retryCount++;
                    continue trysend2;
                }

            }
            if (retryCount == retryNumber) {
                SmartLogger.logThis(Level.WARN, "Error i didn't sent item "
                        + item.getKey() + "  on host " + host + " tried "
                        + retryCount);
            }
        }
    }

    /**
     * Encodes data for transmission to the server.
     * <p>
     * This method encodes the data in the ASCII encoding, defaulting to
     * the platform default encoding if that is somehow unavailable.
     *
     * @param data
     * @return byte[] containing the encoded data
     */
    private byte[] encodeString(String data) {
        try {
            return data.getBytes("ASCII");
        } catch (UnsupportedEncodingException e) {
            return data.getBytes();
        }
    }

    private String base64Encode(String data) {
        return new String(Base64.encodeBase64(encodeString(data)));
    }

    private void send(final String key, final String value) throws IOException {
        final StringBuilder message = new StringBuilder(head);
        //message.append(Base64.encode(key));
        message.append(base64Encode(key));
        message.append(middle);
        //message.append(Base64.encode(value == null ? "" : value));
        message.append(base64Encode(value == null ? "" : value));
        message.append(tail);

        byte[] data = message.toString().getBytes();
        if (log.isDebugEnabled()) {
            SmartLogger.logThis(Level.DEBUG, "------------------------");
        }
        byte[] header = new byte[] {
                'Z', 'B', 'X', 'D', '\1',
                (byte)(data.length & 0xFF),
                (byte)((data.length >> 8) & 0xFF),
                (byte)((data.length >> 16) & 0xFF),
                (byte)((data.length >> 24) & 0xFF),
                '\0', '\0', '\0', '\0'};
        byte[] packet = new byte[header.length + data.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(data, 0, packet, header.length, data.length);

        Socket zabbix = null;
        DataOutputStream out = null;
        InputStream in = null;
        Enumeration<String> serverlist = zabbixServers.keys();

        while (serverlist.hasMoreElements()) {
            String zabbixServer = serverlist.nextElement();
            try {
                zabbix = new Socket(zabbixServer, zabbixServers.get(
                        zabbixServer));
                zabbix.setSoTimeout(TIMEOUT);
                out = new DataOutputStream(zabbix.getOutputStream());
                out.write(packet);
                out.flush();

                in = zabbix.getInputStream();
                final int read = in.read(response);
                if (log.isDebugEnabled()) {
                    SmartLogger.logThis(Level.DEBUG, "received "
                            + new String(response));
                }
                if (read != 2 || response[0] != 'O' || response[1] != 'K') {
                    SmartLogger.logThis(Level.WARN,
                            "received unexpected response '"
                                    + new String(response) + "' for key '"
                                    + key + "'");
                }
            } catch (Exception ex) {
                SmartLogger.logThis(Level.ERROR,
                        "Error contacting Zabbix server " + zabbixServer
                                + "  on port "
                                + zabbixServers.get(zabbixServer));
            } finally {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
                if (zabbix != null) {
                    zabbix.close();
                }

            }
        }
    }

    private void tablespace(final String value) throws Exception {
        String[] tbs = value.split("####");
        String TS_JSON = "{\"data\":[";
        for (int i = 0; i < tbs.length; i++) {
            String[] tb = tbs[i].split("##");
            TS_JSON += "{\"{#TS_NAME}\":\"" + tb[0] + "\"},";
            this.send("ts_info[used," + tb[0] + "]", tb[1]);
            this.send("ts_info[free," + tb[0] + "]", tb[2]);
            this.send("ts_info[total," + tb[0] + "]", tb[3]);
            this.send("ts_info[pfree," + tb[0] + "]", tb[4]);
            this.send("ts_info[pused," + tb[0] + "]", tb[5]);
        }
        TS_JSON = TS_JSON.substring(0, TS_JSON.length() - 1) + "]}";
        this.send("ts_info", TS_JSON);
    }

    private void instance(final String name, final String value) throws Exception{
        String[] insts = value.split("####");
        for (int i = 0; i < insts.length; i++) {
            if (insts[i] == null || ("").equals(insts[i])){
                continue;
            }
            String[] inst = insts[i].split("##");
            if (name.startsWith("global_session_")){
                this.send("session[" + name.substring(15) + "," + Constants.DB_NAME.split(",")[0] + inst[0] + "]", inst[1]);
            }
            else if (name.startsWith("global_waits_")) {
                this.send("waits[" + name.substring(13) + "," + Constants.DB_NAME.split(",")[0] + inst[0] + "]", inst[1]);
            }
        }
    }
}
