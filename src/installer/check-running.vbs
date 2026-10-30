' DroidDrone PC installer: before anything is removed or written, make sure DroidDrone PC isn't running. It is never
' closed for the pilot (that would drop a live drone link): the installer asks them to close it, then Retry, or Cancel
' (nothing changed). Run by Windows Installer (main.wxs, custom action CheckAppRunning); the exe name comes from the
' DDPC_APP_EXE property. Returns 1 = carry on, 2 = the pilot cancelled.

Const MSG_USER_RETRY_CANCEL = &H03000035 ' INSTALLMESSAGE_USER + MB_RETRYCANCEL + MB_ICONWARNING
Const ID_RETRY = 4

Function CheckRunning()
    Dim exe, rec, answer
    CheckRunning = 1
    exe = Session.Property("DDPC_APP_EXE")
    If exe = "" Then Exit Function
    Do While IsRunning(exe)
        Set rec = Session.Installer.CreateRecord(0)
        rec.StringData(0) = Left(exe, Len(exe) - 4) & " is running." & vbCrLf & vbCrLf _
            & "Close it first (disconnect from the drone if connected), then click Retry." & vbCrLf _
            & "Cancel stops the setup without changing anything."
        answer = Session.Message(MSG_USER_RETRY_CANCEL, rec)
        If answer <> ID_RETRY Then
            CheckRunning = 2
            Exit Function
        End If
    Loop
End Function

' True while a process with this exe name runs; False if Windows can't be asked (then the setup carries on as before).
Function IsRunning(exe)
    Dim wmi, found
    IsRunning = False
    On Error Resume Next
    Set wmi = GetObject("winmgmts:{impersonationLevel=impersonate}!\\.\root\cimv2")
    If Err.Number <> 0 Then Exit Function
    Set found = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE Name = '" & Replace(exe, "'", "''") & "'")
    If Err.Number <> 0 Then Exit Function
    IsRunning = (found.Count > 0)
    If Err.Number <> 0 Then IsRunning = False
End Function
