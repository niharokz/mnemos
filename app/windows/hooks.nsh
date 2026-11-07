; Windows installer hooks (NSIS), included by Tauri's installer script.
; Adds "Open with mnemostxt" to the right-click menu of every file and folder,
; like VS Code does. Per-user install, so everything goes under HKCU.

!macro NSIS_HOOK_POSTINSTALL
  WriteRegStr HKCU "Software\Classes\*\shell\mnemostxt" "" "Open with mnemostxt"
  WriteRegStr HKCU "Software\Classes\*\shell\mnemostxt" "Icon" "$INSTDIR\mnemostxt.exe"
  WriteRegStr HKCU "Software\Classes\*\shell\mnemostxt\command" "" '"$INSTDIR\mnemostxt.exe" "%1"'
  WriteRegStr HKCU "Software\Classes\Directory\shell\mnemostxt" "" "Open folder in mnemostxt"
  WriteRegStr HKCU "Software\Classes\Directory\shell\mnemostxt" "Icon" "$INSTDIR\mnemostxt.exe"
  WriteRegStr HKCU "Software\Classes\Directory\shell\mnemostxt\command" "" '"$INSTDIR\mnemostxt.exe" "%1"'
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  DeleteRegKey HKCU "Software\Classes\*\shell\mnemostxt"
  DeleteRegKey HKCU "Software\Classes\Directory\shell\mnemostxt"
!macroend
