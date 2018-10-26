/*  Copyright (C) 2018 D. R. Commander.  All Rights Reserved.
 *
 *  This is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this software; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,
 *  USA.
 */

/*
 * SessionManager.java - TurboVNC Session Manager
 */

package com.turbovnc.vncviewer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

import com.turbovnc.rfb.*;
import com.turbovnc.rdr.*;
import com.turbovnc.network.*;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;

public final class SessionManager extends Tunnel {

  public static final int MAX_SESSIONS = 256;

  public static String createSession(Options opts) throws Exception {
    String host =  Hostname.getHost(opts.serverName);

    vlog.debug("Opening SSH connection to host " + host);
    createTunnelJSch(host, opts);

    boolean firstTime = true;
    while (true) {
      String[] sessions = getSessions(opts.sshSession, host);

      if ((sessions == null || sessions.length <= 0) && firstTime) {
        return startSession(opts.sshSession, host);
      } else {
        SessionManagerDialog dlg = new SessionManagerDialog(sessions, host);
        dlg.initDialog();
        boolean ret = dlg.showDialog();
        if (!ret) return null;
        else if (dlg.getConnectSession() != null) {
          if (dlg.getConnectSession().equals("NEW"))
            return startSession(opts.sshSession, host);
          else {
            if (VncViewer.sessMgrAuto.getValue())
              generateOTP(opts.sshSession, host, dlg.getConnectSession());
            return dlg.getConnectSession();
          }
        } else if (dlg.getKillSession() != null) {
          killSession(opts.sshSession, host, dlg.getKillSession());
        }
      }
      firstTime = false;
    }
  }

  private static String[] getSessions(Session sshSession, String host)
                                      throws Exception {
    ChannelExec channelExec = (ChannelExec)sshSession.openChannel("exec");

    String dir = System.getProperty("turbovnc.serverdir");
    if (dir == null)
      dir = System.getenv("TVNC_SERVERDIR");
    if (dir == null)
      dir = "/opt/TurboVNC";

    String command = dir + "/bin/vncserver -sessionlist";
    channelExec.setCommand(command);
    InputStream stdout = channelExec.getInputStream();
    InputStream stderr = channelExec.getErrStream();
    channelExec.connect();

    BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
    String result = br.readLine(), error = result;
    String[] sessions = null;
    if (result != null)
      sessions = result.split(" ");

    br = new BufferedReader(new InputStreamReader(stderr));
    int nLines = 0;
    while ((result = br.readLine()) != null && nLines < 20) {
      if (error == null) error = result;
      if (nLines == 0) {
        vlog.debug("===============================================================================");
        vlog.debug("SERVER WARNINGS/NOTIFICATIONS:");
      }
      vlog.debug(result);
      nLines++;
    }
    if (nLines > 0)
      vlog.debug("===============================================================================");

    channelExec.disconnect();

    if (channelExec.getExitStatus() == 127) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host + ".\n" +
                               "Is the TurboVNC Server installed in " + dir +
                               " ?");
    } else if (channelExec.getExitStatus() != 0) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host +
                               (error != null ? ":\n    " + error : ""));
    }

    vlog.debug("Available sessions: " + (error != null ? error : "None"));

    return sessions;
  }

  private static String startSession(Session sshSession, String host)
                                     throws Exception {
    vlog.debug("Starting new TurboVNC session on host " + host);

    ChannelExec channelExec = (ChannelExec)sshSession.openChannel("exec");

    String dir = System.getProperty("turbovnc.serverdir");
    if (dir == null)
      dir = System.getenv("TVNC_SERVERDIR");
    if (dir == null)
      dir = "/opt/TurboVNC";

    String args = System.getProperty("turbovnc.serverargs");
    if (args == null)
      args = System.getenv("TVNC_SERVERARGS");

    String command = dir + "/bin/vncserver -sessionstart" +
                     (args != null ? " " + args : "");
    channelExec.setCommand(command);
    InputStream stdout = channelExec.getInputStream();
    InputStream stderr = channelExec.getErrStream();
    channelExec.connect();

    BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
    String result = br.readLine(), error = result;
    String[] sessions = null;
    if (result != null)
      sessions = result.split(" ");

    br = new BufferedReader(new InputStreamReader(stderr));
    int nLines = 0;
    while ((result = br.readLine()) != null && nLines < 20) {
      if (error == null) error = result;
      if (nLines == 0) {
        vlog.debug("===============================================================================");
        vlog.debug("SERVER WARNINGS/NOTIFICATIONS:");
      }
      vlog.debug(result);
      nLines++;
    }
    if (nLines > 0)
      vlog.debug("===============================================================================");

    channelExec.disconnect();

    if (channelExec.getExitStatus() == 127) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host + ".\n" +
                               "Is the TurboVNC Server installed in " + dir +
                               " ?");
    } else if (channelExec.getExitStatus() != 0) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host +
                               (error != null ? ":\n    " + error : ""));
    }

    if (sessions == null)
      throw new ErrorException("Could not parse TurboVNC Server output");

    if (VncViewer.sessMgrAuto.getValue())
      generateOTP(sshSession, host, sessions[0]);

    return sessions[0];
  }

  private static void generateOTP(Session sshSession, String host,
                                  String session) throws Exception {
    vlog.debug("Generating one-time password for session " + host + session);

    VncViewer.noExceptionDialog = true;

    ChannelExec channelExec = (ChannelExec)sshSession.openChannel("exec");

    String dir = System.getProperty("turbovnc.serverdir");
    if (dir == null)
      dir = System.getenv("TVNC_SERVERDIR");
    if (dir == null)
      dir = "/opt/TurboVNC";

    String command = dir + "/bin/vncpasswd -o -display " + session;
    channelExec.setCommand(command);
    InputStream stderr = channelExec.getErrStream();
    channelExec.connect();

    BufferedReader br = new BufferedReader(new InputStreamReader(stderr));
    String result = null, error = null, line;
    int nLines = 0;
    while ((line = br.readLine()) != null && nLines < 20) {
      if (result == null) result = line;
      if (error == null) error = line;
      if (nLines == 0) {
        vlog.debug("===============================================================================");
        vlog.debug("SERVER WARNINGS/NOTIFICATIONS:");
      }
      vlog.debug(line);
      nLines++;
    }
    if (nLines > 0)
      vlog.debug("===============================================================================");

    if (result != null) {
      result = result.replaceAll("\\s", "");
      result = result.replaceAll("^.*:", "");
      VncViewer.password.setParam(result);
    }

    channelExec.disconnect();

    VncViewer.noExceptionDialog = false;

    if (channelExec.getExitStatus() == 127) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host + ".\n" +
                               "Is the TurboVNC Server installed in " + dir +
                               " ?");
    } else if (channelExec.getExitStatus() != 0) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host +
                               (error != null ? ":\n    " + error : ""));
    }
  }

  private static void killSession(Session sshSession, String host,
                                  String session) throws Exception {
    vlog.debug("Killing TurboVNC session " + host + session);

    VncViewer.noExceptionDialog = true;

    ChannelExec channelExec = (ChannelExec)sshSession.openChannel("exec");

    String dir = System.getProperty("turbovnc.serverdir");
    if (dir == null)
      dir = System.getenv("TVNC_SERVERDIR");
    if (dir == null)
      dir = "/opt/TurboVNC";

    String command = dir + "/bin/vncserver -kill " + session;
    channelExec.setCommand(command);
    InputStream stdout = channelExec.getInputStream();
    InputStream stderr = channelExec.getErrStream();
    channelExec.connect();

    BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
    String result = br.readLine(), error = result;

    br = new BufferedReader(new InputStreamReader(stderr));
    int nLines = 0;
    while ((result = br.readLine()) != null && nLines < 20) {
      if (error == null) error = result;
      if (nLines == 0) {
        vlog.debug("===============================================================================");
        vlog.debug("SERVER WARNINGS/NOTIFICATIONS:");
      }
      vlog.debug(result);
      nLines++;
    }
    if (nLines > 0)
      vlog.debug("===============================================================================");

    channelExec.disconnect();

    VncViewer.noExceptionDialog = false;

    if (channelExec.getExitStatus() == 127) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host + ".\n" +
                               "Is the TurboVNC Server installed in " + dir +
                               " ?");
    } else if (channelExec.getExitStatus() != 0) {
      throw new ErrorException("Could not execute\n    " + command + "\n" +
                               "on host " + host +
                               (error != null ? ":\n    " + error : ""));
    }
  }

  private SessionManager() {}
  static LogWriter vlog = new LogWriter("SessionManager");
}
