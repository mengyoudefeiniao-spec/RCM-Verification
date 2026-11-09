// 把工作区映射成 ASCII 盘符（等价 subst），绕开 aapt2/d8 不支持中文路径的问题。
using System;
using System.Runtime.InteropServices;

public static class DosMap {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool DefineDosDeviceW(uint flags, string deviceName, string targetPath);

    const uint DDD_RAW_TARGET_PATH = 0x00000001;
    const uint DDD_REMOVE_DEFINITION = 0x00000002;

    public static bool Map(string drive, string target) {
        return DefineDosDeviceW(DDD_RAW_TARGET_PATH, drive, @"\??\" + target);
    }

    public static bool Unmap(string drive) {
        return DefineDosDeviceW(DDD_REMOVE_DEFINITION | DDD_RAW_TARGET_PATH, drive, null);
    }
}
