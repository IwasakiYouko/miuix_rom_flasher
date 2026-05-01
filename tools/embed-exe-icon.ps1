param(
    [Parameter(Mandatory = $true)]
    [string]$ExePath,
    [Parameter(Mandatory = $true)]
    [string]$IconPath
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ExePath)) {
    throw "EXE not found: $ExePath"
}

if (-not (Test-Path -LiteralPath $IconPath)) {
    throw "ICO not found: $IconPath"
}

Add-Type -TypeDefinition @"
using System;
using System.IO;
using System.Runtime.InteropServices;

public static class ExeIconEmbedder
{
    private const int RT_ICON = 3;
    private const int RT_GROUP_ICON = 14;

    [DllImport("kernel32.dll", EntryPoint = "BeginUpdateResourceW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr BeginUpdateResource(string fileName, [MarshalAs(UnmanagedType.Bool)] bool deleteExistingResources);

    [DllImport("kernel32.dll", EntryPoint = "UpdateResourceW", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UpdateResource(
        IntPtr updateHandle,
        IntPtr type,
        IntPtr name,
        ushort language,
        byte[] data,
        uint dataSize);

    [DllImport("kernel32.dll", EntryPoint = "EndUpdateResourceW", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EndUpdateResource(
        IntPtr updateHandle,
        [MarshalAs(UnmanagedType.Bool)] bool discard);

    public static void ApplyIcon(string exePath, string iconPath)
    {
        var iconBytes = File.ReadAllBytes(iconPath);
        using (var reader = new BinaryReader(new MemoryStream(iconBytes)))
        {
            ushort reserved = reader.ReadUInt16();
            ushort type = reader.ReadUInt16();
            ushort count = reader.ReadUInt16();

            if (reserved != 0 || type != 1 || count == 0)
            {
                throw new InvalidOperationException("Invalid ICO format.");
            }

            var entries = new IconEntry[count];
            for (int i = 0; i < count; i++)
            {
                entries[i] = new IconEntry
                {
                    Width = reader.ReadByte(),
                    Height = reader.ReadByte(),
                    ColorCount = reader.ReadByte(),
                    Reserved = reader.ReadByte(),
                    Planes = reader.ReadUInt16(),
                    BitCount = reader.ReadUInt16(),
                    BytesInRes = reader.ReadUInt32(),
                    ImageOffset = reader.ReadUInt32()
                };
            }

            IntPtr handle = BeginUpdateResource(exePath, false);
            if (handle == IntPtr.Zero)
            {
                throw new InvalidOperationException("BeginUpdateResource failed.");
            }

            try
            {
                for (int i = 0; i < entries.Length; i++)
                {
                    var entry = entries[i];
                    var imageData = new byte[entry.BytesInRes];
                    Buffer.BlockCopy(iconBytes, (int)entry.ImageOffset, imageData, 0, (int)entry.BytesInRes);
                    var iconId = (ushort)(i + 1);

                    if (!UpdateResource(handle, new IntPtr(RT_ICON), new IntPtr(iconId), 0, imageData, (uint)imageData.Length))
                    {
                        throw new InvalidOperationException("UpdateResource RT_ICON failed.");
                    }

                    entry.ResourceId = iconId;
                    entries[i] = entry;
                }

                using (var groupStream = new MemoryStream())
                using (var groupWriter = new BinaryWriter(groupStream))
                {
                    groupWriter.Write((ushort)0);
                    groupWriter.Write((ushort)1);
                    groupWriter.Write((ushort)entries.Length);

                    foreach (var entry in entries)
                    {
                        groupWriter.Write(entry.Width);
                        groupWriter.Write(entry.Height);
                        groupWriter.Write(entry.ColorCount);
                        groupWriter.Write(entry.Reserved);
                        groupWriter.Write(entry.Planes);
                        groupWriter.Write(entry.BitCount);
                        groupWriter.Write(entry.BytesInRes);
                        groupWriter.Write(entry.ResourceId);
                    }

                    var groupBytes = groupStream.ToArray();
                    if (!UpdateResource(handle, new IntPtr(RT_GROUP_ICON), new IntPtr(1), 0, groupBytes, (uint)groupBytes.Length))
                    {
                        throw new InvalidOperationException("UpdateResource RT_GROUP_ICON failed.");
                    }
                }

                if (!EndUpdateResource(handle, false))
                {
                    throw new InvalidOperationException("EndUpdateResource commit failed.");
                }

                handle = IntPtr.Zero;
            }
            finally
            {
                if (handle != IntPtr.Zero)
                {
                    EndUpdateResource(handle, true);
                }
            }
        }
    }

    private struct IconEntry
    {
        public byte Width;
        public byte Height;
        public byte ColorCount;
        public byte Reserved;
        public ushort Planes;
        public ushort BitCount;
        public uint BytesInRes;
        public uint ImageOffset;
        public ushort ResourceId;
    }
}
"@

[ExeIconEmbedder]::ApplyIcon($ExePath, $IconPath)
