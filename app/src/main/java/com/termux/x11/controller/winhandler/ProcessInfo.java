package com.termux.x11.controller.winhandler;

public class ProcessInfo {
    public final int pid;
    public final String name;
    public final long memoryUsage;
    public final int affinityMask;
    public final boolean wow64Process;

    public ProcessInfo(int pid, String name, long memoryUsage, int affinityMask, boolean wow64Process) {
        this.pid = pid;
        this.name = name;
        this.memoryUsage = memoryUsage;
        this.affinityMask = affinityMask;
        this.wow64Process = wow64Process;
    }
}