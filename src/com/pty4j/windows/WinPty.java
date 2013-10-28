package com.pty4j.windows;

import com.pty4j.PtyException;
import com.pty4j.WinSize;
import com.pty4j.util.PtyUtil;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import jtermios.windows.WinAPI;

import java.io.File;
import java.io.IOException;
import java.nio.Buffer;

/**
 * @author traff
 */
public class WinPty {
  private final winpty_t myWinpty;

  private NamedPipe myNamedPipe;
  private boolean myClosed = false;


  public WinPty(String cmdline, String cwd, String env) throws PtyException {
    myWinpty = INSTANCE.winpty_open(80, 25);

    if (myWinpty == null) {
      throw new PtyException("winpty is null");
    }

    int c;

    char[] cmdlineArray = cmdline != null ? toCharArray(cmdline) : null;
    char[] cwdArray = cwd != null ? toCharArray(cwd) : null;
    char[] envArray = env != null ? toCharArray(env) : null;

    if ((c = INSTANCE.winpty_start_process(myWinpty, null, cmdlineArray, cwdArray, envArray)) != 0) {
      throw new PtyException("Error running process:" + c);
    }

    myNamedPipe = new NamedPipe(myWinpty.dataPipe);
  }

  private static char[] toCharArray(String string) {
    char[] array = new char[string.length() + 1];
    System.arraycopy(string.toCharArray(), 0, array, 0, string.length());
    array[string.length()] = 0;
    return array;
  }

  public synchronized void setWinSize(WinSize winSize) {
    if (myClosed) {
      return;
    }
    INSTANCE.winpty_set_size(myWinpty, winSize.ws_col, winSize.ws_row);
  }

  public synchronized void close() {
    if (myClosed) {
      return;
    }

    INSTANCE.winpty_close(myWinpty);

    myClosed = true;
  }

  public synchronized int exitValue() {
    if (myClosed) {
      return -1;
    }
    return INSTANCE.winpty_get_exit_code(myWinpty);
  }

  public synchronized int read(byte[] buf, int len) throws IOException {
    if (myClosed) {
      return 0;
    }

    return myNamedPipe.read(buf, len);
  }

  public void write(byte[] buf, int len) throws IOException {
    if (myClosed) {
      return;
    }

    myNamedPipe.write(buf, len);
  }

  public static class winpty_t extends Structure {
    public WinNT.HANDLE controlPipe;
    public WinNT.HANDLE dataPipe;
  }

  public static final Kern32 KERNEL32 = (Kern32)Native.loadLibrary("kernel32", Kern32.class);

  interface Kern32 extends Library {
    boolean PeekNamedPipe(WinNT.HANDLE hFile,
                          Buffer lpBuffer,
                          int nBufferSize,
                          IntByReference lpBytesRead,
                          IntByReference lpTotalBytesAvail,
                          IntByReference lpBytesLeftThisMessage);

    boolean ReadFile(WinNT.HANDLE handle, Buffer buffer, int i, IntByReference reference, WinBase.OVERLAPPED overlapped);
  }

  public static WinPtyLib INSTANCE = (WinPtyLib)Native.loadLibrary(getLibraryPath(), WinPtyLib.class);

  private static String getLibraryPath() {
    try {
      String folder = PtyUtil.getJarFolder();
      File path = new File(folder, "win");

      if (Platform.is64Bit()) {
        path = new File(path, "x86_64");
      }
      else {
        path = new File(path, "x86");
      }

      File lib = new File(path, "libwinpty.dll");
      if (!lib.exists()) {
        throw new IllegalStateException("Couldn't find lib " + lib.getAbsolutePath());
      }
      return lib.getAbsolutePath();
    }
    catch (Exception e) {
      throw new IllegalStateException("Couldn't detect jar containing folder", e);
    }
  }

  interface WinPtyLib extends Library {
    /*
    * winpty API.
    */

    /*
     * Starts a new winpty instance with the given size.
     *
     * This function creates a new agent process and connects to it.
     */
    winpty_t winpty_open(int cols, int rows);

    /*
     * Start a child process.  Either (but not both) of appname and cmdline may
     * be NULL.  cwd and env may be NULL.  env is a pointer to an environment
     * block like that passed to CreateProcess.
     *
     * This function never modifies the cmdline, unlike CreateProcess.
     *
     * Only one child process may be started.  After the child process exits, the
     * agent will scrape the console output one last time, then close the data pipe
     * once all remaining data has been sent.
     *
     * Returns 0 on success or a Win32 error code on failure.
     */
    int winpty_start_process(winpty_t pc,
                             char[] appname,
                             char[] cmdline,
                             char[] cwd,
                             char[] env);

    /*
     * Returns the exit code of the process started with winpty_start_process,
     * or -1 none is available.
     */
    int winpty_get_exit_code(winpty_t pc);

    /*
     * Returns an overlapped-mode pipe handle that can be read and written
     * like a Unix terminal.
     */
    WinAPI.HANDLE winpty_get_data_pipe(winpty_t pc);

    /*
     * Change the size of the Windows console.
     */
    int winpty_set_size(winpty_t pc, int cols, int rows);

    /*
     * Closes the winpty.
     */
    void winpty_close(winpty_t pc);
  }
}
