package com.github.xingjianluo.ephemeralenvcontrolplanejava.model;

/** 模拟资源规格（申请时给出，用于配额计量与后端分配）。 */
public record ResourceSpec(String kind, String image, int cpu, int memoryMb) {
}
