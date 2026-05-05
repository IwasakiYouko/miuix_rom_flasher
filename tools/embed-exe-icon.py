import ctypes
import struct
import sys

def embed_icon(exe_path, icon_path):
    with open(icon_path, 'rb') as f:
        icon_data = f.read()

    reserved, icon_type, count = struct.unpack('<HHH', icon_data[:6])
    if reserved != 0 or icon_type != 1 or count == 0:
        raise ValueError('Invalid ICO file')

    entries = []
    offset = 6
    for _ in range(count):
        w, h, colors, reserved2, planes, bitcount, size, img_offset = struct.unpack(
            '<BBBBHHII', icon_data[offset:offset+16]
        )
        entries.append({
            'w': w, 'h': h, 'colors': colors, 'reserved': reserved2,
            'planes': planes, 'bitcount': bitcount, 'size': size, 'offset': img_offset,
        })
        offset += 16

    kernel32 = ctypes.windll.kernel32

    # RT_ICON = 3, RT_GROUP_ICON = 14
    RT_ICON = ctypes.c_wchar_p(3)
    RT_GROUP_ICON = ctypes.c_wchar_p(14)

    BeginUpdateResourceW = kernel32.BeginUpdateResourceW
    BeginUpdateResourceW.argtypes = [ctypes.c_wchar_p, ctypes.c_bool]
    BeginUpdateResourceW.restype = ctypes.c_void_p

    UpdateResourceW = kernel32.UpdateResourceW
    UpdateResourceW.argtypes = [
        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
        ctypes.c_ushort, ctypes.c_void_p, ctypes.c_uint,
    ]
    UpdateResourceW.restype = ctypes.c_bool

    EndUpdateResourceW = kernel32.EndUpdateResourceW
    EndUpdateResourceW.argtypes = [ctypes.c_void_p, ctypes.c_bool]
    EndUpdateResourceW.restype = ctypes.c_bool

    handle = BeginUpdateResourceW(exe_path, False)
    if not handle:
        raise OSError(f'BeginUpdateResourceW failed: {ctypes.get_last_error()}')

    try:
        for i, entry in enumerate(entries):
            img = icon_data[entry['offset']:entry['offset'] + entry['size']]
            icon_id = i + 1
            buf = ctypes.create_string_buffer(img)
            if not UpdateResourceW(
                handle, RT_ICON, ctypes.c_void_p(icon_id), 0, buf, len(img)
            ):
                raise OSError(f'UpdateResource RT_ICON failed: {ctypes.get_last_error()}')
            entry['res_id'] = icon_id

        group = struct.pack('<HHH', 0, 1, len(entries))
        for entry in entries:
            group += struct.pack(
                '<BBBBHHIH',
                entry['w'], entry['h'], entry['colors'], entry['reserved'],
                entry['planes'], entry['bitcount'], entry['size'], entry['res_id'],
            )
        group_buf = ctypes.create_string_buffer(group)
        if not UpdateResourceW(
            handle, RT_GROUP_ICON, ctypes.c_void_p(1), 0, group_buf, len(group)
        ):
            raise OSError(f'UpdateResource RT_GROUP_ICON failed: {ctypes.get_last_error()}')

        if not EndUpdateResourceW(handle, False):
            raise OSError(f'EndUpdateResourceW failed: {ctypes.get_last_error()}')
        handle = None
    finally:
        if handle:
            EndUpdateResourceW(handle, True)

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f'Usage: {sys.argv[0]} <exe> <ico>')
        sys.exit(1)
    embed_icon(sys.argv[1], sys.argv[2])
    print('Icon embedded successfully.')
