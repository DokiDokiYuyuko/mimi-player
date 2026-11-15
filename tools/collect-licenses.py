"""Collect upstream notices, preserve their text, and generate the offline license viewer."""
from pathlib import Path
import concurrent.futures, hashlib, json, urllib.request, zipfile
root=Path(__file__).resolve().parents[1]
dest=root/'third-party/licenses';dest.mkdir(parents=True,exist_ok=True)
raw='https://raw.githubusercontent.com/'
sources=[
 ('LGPL-2.1','LibVLC 3.7.7 / VLC 3.0.25','LGPL-2.1',raw+'videolan/vlc/3.0.x/COPYING.LIB'),
 ('LGPL-3.0','FFmpegKit 8.1.9 / FFmpeg n8.1.3','LGPL-3.0',raw+'ffmpegkit-maintained/ffmpeg/main/LICENSE'),
 ('GPL-3.0','GNU GPL v3（LGPL v3 引用）','GPL-3.0',raw+'FFmpeg/FFmpeg/n8.1.3/COPYING.GPLv3'),
 ('Apache-2.0','AndroidX / Kotlin / Zip4j / libarchive-android','Apache-2.0','https://www.apache.org/licenses/LICENSE-2.0.txt'),
 ('LLVM','LLVM libc++','Apache-2.0 WITH LLVM-exception',raw+'llvm/llvm-project/llvmorg-18.1.8/libcxx/LICENSE.TXT'),
 ('libarchive-android','libarchive-android 1.1.7','Apache-2.0',raw+'zhanghai/libarchive-android/v1.1.7/LICENSE'),
 ('libarchive','libarchive 3.8.8','BSD and compatible licenses',raw+'libarchive/libarchive/7219b0134d771dc4b51bf86b4d01761b87398b1b/COPYING'),
 ('bzip2','bzip2','bzip2 license',raw+'libarchive/bzip2/6a8690fc8d26c815e798c588f796eabe9d684cf0/LICENSE'),
 ('lz4','LZ4','BSD-2-Clause',raw+'lz4/lz4/ebb370ca83af193212df4dcbadcc5d87bc0de2f0/lib/LICENSE'),
 ('mbedtls','Mbed TLS','Apache-2.0 OR GPL-2.0-or-later',raw+'Mbed-TLS/mbedtls/068ff080b369adfac81509f9b57b2afabaf82dc5/LICENSE'),
 ('xz','XZ Utils','Multiple licenses; see COPYING',raw+'tukaani-project/xz/d3e650e63c110e830fd5391e7f8b45df0b91d3da/COPYING'),
 ('xz-0BSD','XZ liblzma','0BSD',raw+'tukaani-project/xz/d3e650e63c110e830fd5391e7f8b45df0b91d3da/COPYING.0BSD'),
 ('zstd','Zstandard','BSD-3-Clause',raw+'facebook/zstd/f8745da6ff1ad1e7bab384bd1f9d742439278e99/LICENSE'),
 ('juniversalchardet','juniversalchardet 2.5.2','MPL-1.1 / GPL-2.0 / LGPL-2.1',raw+'albfernandez/juniversalchardet/v2.5.2/LICENSE'),
 ('FFmpegKit-NOTICES','FFmpegKit 第三方声明','Upstream notices',raw+'ffmpegkit-maintained/ffmpeg/main/THIRD-PARTY-NOTICES.txt'),
]
def retrieve(item):
    ident,name,license,url=item;file=dest/(ident+'.txt')
    if not file.exists():
        req=urllib.request.Request(url,headers={'User-Agent':'MIMI-source-packager/1.2'})
        with urllib.request.urlopen(req,timeout=35) as response:data=response.read()
        assert len(data)>100 and not data.lstrip().startswith(b'<!DOCTYPE html>'),url
        file.write_bytes(data)
    data=file.read_bytes()
    return {'id':ident,'name':name,'license':license,'url':url,'sha256':hashlib.sha256(data).hexdigest(),'text':data.decode('utf-8-sig','replace')}
records=[];errors=[]
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    futures={pool.submit(retrieve,s):s for s in sources}
    for future,item in futures.items():
        try:records.append(future.result())
        except Exception as e:errors.append(str(item[0])+': '+str(e))
if errors:raise RuntimeError('\n'.join(errors))
with zipfile.ZipFile(root/'app/libs/ffmpeg-kit-audio-8.1.9-vlc-cxx.aar') as archive:
    for name in archive.namelist():
        if name.startswith('res/raw/license') and name.endswith('.txt'):
            data=archive.read(name);ident='ffmpeg-'+Path(name).stem; (dest/(ident+'.txt')).write_bytes(data)
            records.append({'id':ident,'name':Path(name).stem.replace('license_','').replace('license','FFmpegKit'),'license':'原始许可声明','url':'https://github.com/ffmpegkit-maintained/ffmpeg','sha256':hashlib.sha256(data).hexdigest(),'text':data.decode('utf-8','replace')})
font=root/'app/src/main/assets/fonts/OFL.txt';data=font.read_bytes();(dest/'ZCOOLKuaiLe-OFL.txt').write_bytes(data)
records.append({'id':'font','name':'站酷快乐体','license':'SIL OFL 1.1','url':'https://github.com/google/fonts/tree/main/ofl/zcoolkuaile','sha256':hashlib.sha256(data).hexdigest(),'text':data.decode('utf-8')})
(root/'third-party/license-sources.json').write_text(json.dumps([{k:v for k,v in r.items() if k!='text'} for r in records],ensure_ascii=False,indent=2)+'\n','utf-8')
(root/'app/src/main/assets/third-party.js').write_text('window.MIMI_LICENSES='+json.dumps(records,ensure_ascii=False).replace('<','\\u003c')+';\n','utf-8')
print(json.dumps({'licenses':len(records),'bytes':sum(len(r['text']) for r in records)}))
