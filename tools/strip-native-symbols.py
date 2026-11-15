"""Strip non-runtime symbols while proving every allocated ELF section is unchanged.

Run after prepare-native-libs.py: python tools/strip-native-symbols.py --llvm-strip /path/to/llvm-strip
Only libraries with non-allocated .debug_* / .symtab sections are rewritten.
"""
from pathlib import Path
import argparse,hashlib,io,json,struct,subprocess,tempfile,zipfile
parser=argparse.ArgumentParser();parser.add_argument('--llvm-strip',required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
def sections(data):
    assert data[:6]==b'\x7fELF\x02\x01'
    offset=struct.unpack_from('<Q',data,40)[0];size,count,strings=struct.unpack_from('<HHH',data,58)
    headers=[struct.unpack_from('<IIQQQQIIQQ',data,offset+i*size) for i in range(count)]
    table=headers[strings];names=data[table[4]:table[4]+table[5]];result={}
    for item in headers:
        start=item[0];name=names[start:names.index(b'\0',start)].decode('ascii')
        result[name]={'type':item[1],'flags':item[2],'address':item[3],'size':item[5],
                      'bytes':b'' if item[1]==8 else data[item[4]:item[4]+item[5]]}
    return result
records=[]
for path in sorted((root/'app/libs').glob('*.aar')):
    original=path.read_bytes();incoming=zipfile.ZipFile(io.BytesIO(original));changed=False;target=io.BytesIO()
    with zipfile.ZipFile(target,'w') as outgoing:
        for info in incoming.infolist():
            data=incoming.read(info.filename)
            if info.filename.endswith('.so'):
                before=sections(data);debug=[n for n,v in before.items() if not v['flags']&2 and (n.startswith('.debug') or n=='.symtab')]
                if debug:
                    with tempfile.TemporaryDirectory(prefix='mimi-strip-') as folder:
                        source=Path(folder)/'input.so';output=Path(folder)/'output.so';source.write_bytes(data)
                        subprocess.run([args.llvm_strip,'--strip-unneeded','-o',str(output),str(source)],check=True)
                        stripped=output.read_bytes()
                    after=sections(stripped)
                    runtime={n:v for n,v in before.items() if v['flags']&2}
                    assert set(runtime)=={n for n,v in after.items() if v['flags']&2}
                    for name,value in runtime.items():assert value==after[name],info.filename+': runtime section changed: '+name
                    assert not any(n.startswith('.debug') or n=='.symtab' for n in after)
                    records.append({'archive':path.name,'library':info.filename,'before':len(data),'after':len(stripped),'saved':len(data)-len(stripped),'runtimeSectionsUnchanged':True,'allocatedSections':len(runtime),'beforeSha256':hashlib.sha256(data).hexdigest(),'sha256':hashlib.sha256(stripped).hexdigest()})
                    data=stripped;changed=True
            outgoing.writestr(info,data)
    if changed:path.write_bytes(target.getvalue())
if records:
    (root/'third-party/symbol-stripping.json').write_text(json.dumps(records,indent=2)+'\n','utf-8')
    manifest=root/'third-party/native-artifacts.json'
    if manifest.exists():
        artifacts=json.loads(manifest.read_text('utf-8'))
        for item in artifacts:
            item['sha256']=hashlib.sha256((root/'app/libs'/item['file']).read_bytes()).hexdigest()
        manifest.write_text(json.dumps(artifacts,indent=2)+'\n','utf-8')
print(json.dumps({'librariesStripped':len(records),'bytesRemoved':sum(r['saved'] for r in records),'runtimeSectionsUnchanged':True}))
