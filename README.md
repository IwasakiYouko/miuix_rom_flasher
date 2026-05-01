# Miuix Win Sample

这是一个基于 [miuix-mingw](https://github.com/YuKongA/miuix-mingw) 的最小 Windows 示例。

当前工程通过 `includeBuild("miuix-mingw-ref")` 直接引用仓库源码模块来构建。
这样做的原因是：当前 Maven Central 发布包还没有可直接消费的 `mingw_x64` 变体。

## 功能

- `mingwX64` 单窗口桌面程序
- 使用 `MiuixTheme`、`TopAppBar`、`Card`、`TextButton`
- 使用 `SwitchPreference`、`CheckboxPreference`
- 内置 `ANGLE` 运行时 DLL 复制逻辑

## 构建

```powershell
.\gradlew.bat linkReleaseExecutableMingwX64
```

## 运行产物

默认输出路径：

```text
app\build\bin\mingwX64\releaseExecutable\MiuixWinSample.exe
```

参考仓库已保存在当前目录下的 `miuix-mingw-ref`。
