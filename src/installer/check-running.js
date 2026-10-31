// DRAUPNE Link installer: before anything is removed or written, make sure DRAUPNE Link isn't running. It is never
// closed for the pilot (that would drop a live drone link): the installer asks them to close it, then Retry, or Cancel
// (nothing changed). Run by Windows Installer (main.wxs, custom action CheckAppRunning); the exe name comes from the
// DDPC_APP_EXE property (several, split by ";"). Returns 1 = carry on, 2 = the pilot cancelled. JScript, not VBScript: Windows is phasing
// VBScript out, and where it is switched off a VBScript action fails every install.

var MSG_USER_RETRY_CANCEL = 0x03000035; // INSTALLMESSAGE_USER + MB_RETRYCANCEL + MB_ICONWARNING
var ID_RETRY = 4;

function CheckRunning() {
    var names = Session.Property("DDPC_APP_EXE").split(";");
    for (var i = 0; i < names.length; i++) {
        var exe = names[i];
        if (exe == "") continue;
        while (isRunning(exe)) {
            var rec = Session.Installer.CreateRecord(0);
            rec.StringData(0) = exe.substring(0, exe.length - 4) + " is running.\r\n\r\n"
                + "Close it first (disconnect from the drone if connected), then click Retry.\r\n"
                + "Cancel stops the setup without changing anything.";
            if (Session.Message(MSG_USER_RETRY_CANCEL, rec) != ID_RETRY) return 2;
        }
    }
    return 1;
}

// True while a process with this exe name runs; false if Windows can't be asked (then the setup carries on as before).
function isRunning(exe) {
    try {
        var found = GetObject("winmgmts:").ExecQuery(
            "SELECT ProcessId FROM Win32_Process WHERE Name = '" + exe.replace(/'/g, "''") + "'");
        return found.Count > 0;
    } catch (e) {
        return false;
    }
}
