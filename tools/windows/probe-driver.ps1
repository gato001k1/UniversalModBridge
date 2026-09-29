<#
.SYNOPSIS
  Self-contained Windows desktop probe driver for visual verification of runs.

.DESCRIPTION
  No new dependencies: Windows PowerShell 5.1 + Add-Type inline C# only.
  Uses System.Drawing screen capture via Graphics.CopyFromScreen, and user32
  P/Invoke (SendInput / mouse_event / keybd_event for input, GetCursorPos for
  round-trip verify, GetSystemMetrics for screen size).

USAGE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -Screenshot <out.png> ["partial window title"]
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -Screenshot <out.png> -WindowTitle "partial title"
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -MoveMouse <x> <y>
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -MoveMouse -X <x> -Y <y>
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -Click [left|right|middle]
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -Key <keyname>
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -GetCursor
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -WindowRect "<partial title>"
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -ListWindows
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\windows\probe-driver.ps1 -ScreenSize

  Key names: Enter, Tab, Esc/Escape, Space, Backspace, Delete, Insert, Home,
  End, PageUp/PageDown, Up/Down/Left/Right, Shift, Ctrl, Alt, LWin, RWin,
  F1-F24, A-Z, 0-9, or any single character (shift handled via VkKeyScan).
#>
[CmdletBinding(PositionalBinding = $false)]
param(
  [string]$Screenshot = "",
  [string]$WindowTitle = "",
  [switch]$MoveMouse,
  [int]$X,
  [int]$Y,
  [switch]$Click,
  [string]$Button = "",
  [string]$Key = "",
  [switch]$GetCursor,
  [string]$WindowRect = "",
  [switch]$ListWindows,
  [switch]$ScreenSize,
  [switch]$Help,
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$Rest = @()
)

$ErrorActionPreference = "Stop"

function Show-Usage {
  Write-Output "probe-driver.ps1 - self-contained desktop probe (PS 5.1, no new deps)"
  Write-Output "  -Screenshot <out.png> [title]  capture full screen, or window matching partial title"
  Write-Output "  -MoveMouse <x> <y>             move cursor (SendInput absolute + SetCursorPos)"
  Write-Output "  -Click [left|right|middle]     click at current cursor (default left)"
  Write-Output "  -Key <name>                    press key (Enter, Tab, Esc, Space, arrows, F1-F24, A-Z, 0-9, single char)"
  Write-Output "  -GetCursor                     print cursor position (GetCursorPos)"
  Write-Output "  -WindowRect ""<title>""          print rect of window(s) matching partial title"
  Write-Output "  -ListWindows                   list visible top-level windows + shell detection"
  Write-Output "  -ScreenSize                    print primary screen size (GetSystemMetrics)"
}

$csharp = @'
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class ProbeDriver
{
    public const int SM_CXSCREEN = 0;
    public const int SM_CYSCREEN = 1;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const byte VK_SHIFT = 0x10;
    private const uint INPUT_MOUSE = 0;

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X; public int Y; }

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    public struct MOUSEINPUT
    {
        public int dx; public int dy; public uint mouseData;
        public uint dwFlags; public uint time; public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct KEYBDINPUT
    {
        public ushort wVk; public ushort wScan; public uint dwFlags;
        public uint time; public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    public struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct INPUT { public uint type; public InputUnion u; }

    public class WindowInfo
    {
        public IntPtr Hwnd;
        public string Title;
        public string ClassName;
        public uint Pid;
        public RECT Rect;
    }

    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT lpPoint);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, int dx, int dy, uint dwData, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int nIndex);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, StringBuilder lpClassName, int nMaxCount);
    [DllImport("user32.dll")] public static extern short VkKeyScan(char ch);
    [DllImport("user32.dll")] public static extern uint MapVirtualKey(uint uCode, uint uMapType);

    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    private static List<WindowInfo> _enum;

    private static bool EnumCallback(IntPtr hWnd, IntPtr lParam)
    {
        try
        {
            if (!IsWindowVisible(hWnd)) return true;
            int len = GetWindowTextLength(hWnd);
            if (len <= 0) return true;
            StringBuilder sb = new StringBuilder(len + 1);
            GetWindowText(hWnd, sb, sb.Capacity);
            string title = sb.ToString();
            if (title == null || title.Trim().Length == 0) return true;
            StringBuilder cn = new StringBuilder(256);
            GetClassName(hWnd, cn, cn.Capacity);
            RECT r;
            if (!GetWindowRect(hWnd, out r)) return true;
            uint pid = 0;
            GetWindowThreadProcessId(hWnd, out pid);
            WindowInfo wi = new WindowInfo();
            wi.Hwnd = hWnd; wi.Title = title; wi.ClassName = cn.ToString(); wi.Pid = pid; wi.Rect = r;
            _enum.Add(wi);
        }
        catch { }
        return true;
    }

    public static WindowInfo[] ListTopLevelWindows()
    {
        _enum = new List<WindowInfo>();
        EnumWindows(new EnumWindowsProc(EnumCallback), IntPtr.Zero);
        return _enum.ToArray();
    }

    public static WindowInfo[] FindWindows(string partial)
    {
        List<WindowInfo> all = new List<WindowInfo>(ListTopLevelWindows());
        if (partial == null || partial.Trim().Length == 0) return all.ToArray();
        string p = partial.ToLowerInvariant();
        List<WindowInfo> hit = new List<WindowInfo>();
        foreach (WindowInfo w in all)
        {
            if (w.Title != null && w.Title.ToLowerInvariant().Contains(p)) hit.Add(w);
        }
        return hit.ToArray();
    }

    public static int ScreenWidth() { return GetSystemMetrics(SM_CXSCREEN); }
    public static int ScreenHeight() { return GetSystemMetrics(SM_CYSCREEN); }

    public static POINT GetCursor() { POINT pt; GetCursorPos(out pt); return pt; }

    public static void MoveMouse(int x, int y)
    {
        int w = ScreenWidth();
        int h = ScreenHeight();
        if (w > 1 && h > 1)
        {
            int nx = (int)Math.Round((double)x * 65535.0 / (double)(w - 1));
            int ny = (int)Math.Round((double)y * 65535.0 / (double)(h - 1));
            if (nx < 0) nx = 0; if (nx > 65535) nx = 65535;
            if (ny < 0) ny = 0; if (ny > 65535) ny = 65535;
            INPUT[] inp = new INPUT[1];
            inp[0].type = INPUT_MOUSE;
            inp[0].u.mi.dx = nx;
            inp[0].u.mi.dy = ny;
            inp[0].u.mi.mouseData = 0;
            inp[0].u.mi.dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE;
            inp[0].u.mi.time = 0;
            inp[0].u.mi.dwExtraInfo = UIntPtr.Zero;
            SendInput(1, inp, Marshal.SizeOf(typeof(INPUT)));
        }
        SetCursorPos(x, y);
    }

    public static void Click(string button)
    {
        string b = (button == null) ? "left" : button.ToLowerInvariant();
        uint down, up;
        if (b == "right") { down = MOUSEEVENTF_RIGHTDOWN; up = MOUSEEVENTF_RIGHTUP; }
        else if (b == "middle") { down = MOUSEEVENTF_MIDDLEDOWN; up = MOUSEEVENTF_MIDDLEUP; }
        else { down = MOUSEEVENTF_LEFTDOWN; up = MOUSEEVENTF_LEFTUP; }
        mouse_event(down, 0, 0, 0, UIntPtr.Zero);
        Thread.Sleep(30);
        mouse_event(up, 0, 0, 0, UIntPtr.Zero);
    }

    public static void PressKey(byte vk)
    {
        byte scan = (byte)(MapVirtualKey((uint)vk, 0) & 0xFF);
        keybd_event(vk, scan, 0, UIntPtr.Zero);
        Thread.Sleep(30);
        keybd_event(vk, scan, KEYEVENTF_KEYUP, UIntPtr.Zero);
    }

    public static void PressChar(char c)
    {
        short vks = VkKeyScan(c);
        if (vks == -1) throw new ArgumentException("No virtual-key translation for character '" + c + "'");
        byte vk = (byte)(vks & 0xFF);
        int shift = (vks >> 8) & 0xFF;
        byte scanShift = (byte)(MapVirtualKey((uint)VK_SHIFT, 0) & 0xFF);
        byte scan = (byte)(MapVirtualKey((uint)vk, 0) & 0xFF);
        bool needShift = (shift & 1) != 0;
        if (needShift) { keybd_event(VK_SHIFT, scanShift, 0, UIntPtr.Zero); Thread.Sleep(10); }
        keybd_event(vk, scan, 0, UIntPtr.Zero);
        Thread.Sleep(30);
        keybd_event(vk, scan, KEYEVENTF_KEYUP, UIntPtr.Zero);
        if (needShift) { Thread.Sleep(10); keybd_event(VK_SHIFT, scanShift, KEYEVENTF_KEYUP, UIntPtr.Zero); }
    }

    public static string CapturePrimary(string path)
    {
        int w = ScreenWidth();
        int h = ScreenHeight();
        using (Bitmap bmp = new Bitmap(w, h))
        {
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.CopyFromScreen(0, 0, 0, 0, new Size(w, h));
            }
            bmp.Save(path, ImageFormat.Png);
        }
        return path;
    }

    public static RECT CaptureWindow(string partial, string path)
    {
        WindowInfo[] hit = FindWindows(partial);
        if (hit.Length == 0) throw new ArgumentException("No visible window matching title '" + partial + "'");
        WindowInfo win = hit[0];
        int w = win.Rect.Right - win.Rect.Left;
        int h = win.Rect.Bottom - win.Rect.Top;
        if (w <= 0 || h <= 0) throw new InvalidOperationException("Window rect is empty");
        using (Bitmap bmp = new Bitmap(w, h))
        {
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.CopyFromScreen(win.Rect.Left, win.Rect.Top, 0, 0, new Size(w, h));
            }
            bmp.Save(path, ImageFormat.Png);
        }
        return win.Rect;
    }
}
'@

if (-not ([System.Management.Automation.PSTypeName]'ProbeDriver').Type) {
  Add-Type -TypeDefinition $csharp -ReferencedAssemblies @("System.Drawing")
}

$vkMap = @{
  "enter" = 0x0D; "return" = 0x0D; "tab" = 0x09;
  "escape" = 0x1B; "esc" = 0x1B; "space" = 0x20;
  "backspace" = 0x08; "back" = 0x08; "delete" = 0x2E; "del" = 0x2E;
  "insert" = 0x2D; "ins" = 0x2D; "home" = 0x24; "end" = 0x23;
  "pageup" = 0x21; "pgup" = 0x21; "pagedown" = 0x22; "pgdn" = 0x22;
  "up" = 0x26; "down" = 0x28; "left" = 0x25; "right" = 0x27;
  "shift" = 0x10; "ctrl" = 0x11; "control" = 0x11; "alt" = 0x12; "menu" = 0x12;
  "capslock" = 0x14; "caps" = 0x14; "numlock" = 0x90; "scrolllock" = 0x91;
  "printscreen" = 0x2C; "prtsc" = 0x2C; "pause" = 0x13; "break" = 0x03;
  "lwin" = 0x5B; "rwin" = 0x5C; "apps" = 0x5D;
  "multiply" = 0x6A; "add" = 0x6B; "subtract" = 0x6D; "decimal" = 0x6E; "divide" = 0x6F
}
for ($i = 1; $i -le 24; $i++) { $vkMap["f" + $i] = 0x6F + $i }
for ($i = 0; $i -le 9; $i++) { $vkMap["$i"] = 0x30 + $i }
for ($c = 65; $c -le 90; $c++) { $vkMap[([char]$c).ToString().ToLowerInvariant()] = $c }

function Invoke-ProbeKeyPress {
  param([string]$Name)
  $n = $Name.Trim()
  $lk = $n.ToLowerInvariant()
  if ($vkMap.ContainsKey($lk)) {
    $vk = [byte]$vkMap[$lk]
    [ProbeDriver]::PressKey($vk)
    return ("vk=0x{0:X2}" -f $vk)
  }
  if ($n.Length -eq 1) {
    [ProbeDriver]::PressChar($n[0])
    return ("char={0}" -f $n)
  }
  throw ("Unknown key name '{0}'. Try Enter, Tab, Esc, Space, arrows, F1-F24, A-Z, 0-9." -f $Name)
}

# ---- Parse trailing positional args so these all work:
#   -MoveMouse 500 500 | -MoveMouse 500,500 | -MoveMouse -X 500 -Y 500
#   -Click | -Click right | -Click -Button middle
#   -Screenshot out.png ["partial title"]
$flat = @()
if ($Rest) {
  foreach ($r in $Rest) {
    foreach ($p in ($r -split ",")) {
      $pp = $p.Trim()
      if ($pp -ne "") { $flat += $pp }
    }
  }
}
$intVals = New-Object System.Collections.ArrayList
$strVals = New-Object System.Collections.ArrayList
foreach ($tok in $flat) {
  $v = 0
  if ([int]::TryParse($tok, [ref]$v)) { [void]$intVals.Add($v) } else { [void]$strVals.Add($tok) }
}

if ($MoveMouse) {
  if (-not $PSBoundParameters.ContainsKey("X")) {
    if ($intVals.Count -lt 1) { throw "MoveMouse needs X Y (usage: -MoveMouse <x> <y>)" }
    $X = $intVals[0]; $intVals.RemoveAt(0)
  }
  if (-not $PSBoundParameters.ContainsKey("Y")) {
    if ($intVals.Count -lt 1) { throw "MoveMouse needs X Y (usage: -MoveMouse <x> <y>)" }
    $Y = $intVals[0]; $intVals.RemoveAt(0)
  }
}

if ($Click) {
  if ([string]::IsNullOrEmpty($Button)) {
    $foundBtn = $null
    for ($i = 0; $i -lt $strVals.Count; $i++) {
      $lt = $strVals[$i].ToLowerInvariant()
      if ($lt -eq "left" -or $lt -eq "right" -or $lt -eq "middle") { $foundBtn = $lt; $strVals.RemoveAt($i); break }
    }
    if ($foundBtn) { $Button = $foundBtn } else { $Button = "left" }
  }
}

if ($Screenshot -ne "") {
  if ([string]::IsNullOrEmpty($WindowTitle)) {
    if ($strVals.Count -gt 0) { $WindowTitle = ($strVals -join " "); $strVals.Clear() }
  } else {
    if ($strVals.Count -gt 0) { $WindowTitle = $WindowTitle + " " + ($strVals -join " "); $strVals.Clear() }
  }
}

if ((-not [string]::IsNullOrEmpty($WindowRect)) -and ($strVals.Count -gt 0)) {
  $WindowRect = $WindowRect + " " + ($strVals -join " ")
  $strVals.Clear()
}

if ($strVals.Count -gt 0) { throw ("Unrecognized argument(s): {0}" -f ($strVals -join ", ")) }
if ($intVals.Count -gt 0) { throw ("Unrecognized numeric argument(s): {0}" -f ($intVals -join ", ")) }

# ---- Dispatch ----
if ($Help) { Show-Usage; exit 0 }

$acted = $false

if ($ScreenSize) {
  $sw = [ProbeDriver]::ScreenWidth()
  $sh = [ProbeDriver]::ScreenHeight()
  Write-Output ("SCREEN w={0} h={1}" -f $sw, $sh)
  $acted = $true
}

if ($MoveMouse) {
  [ProbeDriver]::MoveMouse($X, $Y)
  Start-Sleep -Milliseconds 120
  $p = [ProbeDriver]::GetCursor()
  Write-Output ("MOVED x={0} y={1} cursor=({2},{3})" -f $X, $Y, $p.X, $p.Y)
  $acted = $true
}

if ($Click) {
  $b = $Button.ToLowerInvariant()
  if ($b -ne "left" -and $b -ne "right" -and $b -ne "middle") { throw ("Unknown button '{0}'. Use left, right, or middle." -f $Button) }
  [ProbeDriver]::Click($b)
  Write-Output ("CLICK button={0}" -f $b)
  $acted = $true
}

if (-not [string]::IsNullOrEmpty($Key)) {
  $detail = Invoke-ProbeKeyPress $Key
  Write-Output ("KEY name={0} {1}" -f $Key, $detail)
  $acted = $true
}

if ($GetCursor) {
  $p = [ProbeDriver]::GetCursor()
  Write-Output ("CURSOR x={0} y={1}" -f $p.X, $p.Y)
  $acted = $true
}

if (-not [string]::IsNullOrEmpty($WindowRect)) {
  $foundWins = [ProbeDriver]::FindWindows($WindowRect)
  if (($foundWins -eq $null) -or ($foundWins.Count -eq 0)) { throw ("No visible window matching title '{0}'" -f $WindowRect) }
  foreach ($win in $foundWins) {
    $ww = $win.Rect.Right - $win.Rect.Left
    $wh = $win.Rect.Bottom - $win.Rect.Top
    $t = $win.Title -replace "\s+", " "
    Write-Output ("WINDOW hwnd=0x{0:X8} title={1} class={2} rect={3},{4},{5},{6} size={7}x{8}" -f $win.Hwnd.ToInt64(), $t, $win.ClassName, $win.Rect.Left, $win.Rect.Top, $win.Rect.Right, $win.Rect.Bottom, $ww, $wh)
  }
  $acted = $true
}

if ($Screenshot -ne "") {
  $full = $Screenshot
  if (-not [System.IO.Path]::IsPathRooted($full)) { $full = Join-Path -Path (Get-Location).Path -ChildPath $full }
  $dir = Split-Path -Parent $full
  if (($dir -ne "") -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  if (Test-Path -LiteralPath $full) { Remove-Item -Force -LiteralPath $full }
  if (-not [string]::IsNullOrEmpty($WindowTitle)) {
    $r = [ProbeDriver]::CaptureWindow($WindowTitle, $full)
    $ww = $r.Right - $r.Left
    $wh = $r.Bottom - $r.Top
    $bytes = (Get-Item -LiteralPath $full).Length
    Write-Output ("SCREENSHOT path={0} bytes={1} w={2} h={3} window={4}" -f $full, $bytes, $ww, $wh, $WindowTitle)
  } else {
    [ProbeDriver]::CapturePrimary($full)
    $sw = [ProbeDriver]::ScreenWidth()
    $sh = [ProbeDriver]::ScreenHeight()
    $bytes = (Get-Item -LiteralPath $full).Length
    Write-Output ("SCREENSHOT path={0} bytes={1} w={2} h={3}" -f $full, $bytes, $sw, $sh)
  }
  $acted = $true
}

if ($ListWindows) {
  $wins = [ProbeDriver]::ListTopLevelWindows()
  $count = 0
  $shellCount = 0
  $shellNames = New-Object System.Collections.ArrayList
  foreach ($win in $wins) {
    $count++
    $proc = "?"
    try { $po = Get-Process -Id ([int]$win.Pid) -ErrorAction Stop; $proc = $po.ProcessName } catch { $proc = "?" }
    $t = $win.Title -replace "\s+", " "
    if ($t.Length -gt 120) { $t = $t.Substring(0, 120) }
    $isShell = ($win.ClassName -eq "Shell_TrayWnd") -or ($win.ClassName -eq "Progman") -or ($win.Title -eq "Program Manager") -or ($proc -eq "explorer")
    if ($isShell) { $shellCount++; [void]$shellNames.Add($t + " [" + $win.ClassName + "/" + $proc + "]") }
    Write-Output ("HWND=0x{0:X8} PID={1} PROC={2} CLASS={3} TITLE={4} RECT={5},{6},{7},{8}" -f $win.Hwnd.ToInt64(), $win.Pid, $proc, $win.ClassName, $t, $win.Rect.Left, $win.Rect.Top, $win.Rect.Right, $win.Rect.Bottom)
  }
  $shellWord = "no"
  if ($shellCount -gt 0) { $shellWord = "yes" }
  Write-Output ("WINDOWS count={0} shell={1} shellHits={2}" -f $count, $shellWord, $shellCount)
  foreach ($s in $shellNames) { Write-Output ("SHELL title={0}" -f $s) }
  $acted = $true
}

if (-not $acted) { Show-Usage; exit 2 }
