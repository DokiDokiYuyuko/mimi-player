// One geometry/palette source for Android vector resources and reviewable SVGs.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const ns = 'xmlns:android="http://schemas.android.com/apk/res/android"';
const aapt = 'xmlns:aapt="http://schemas.android.com/aapt"';
const square = 'M0,0H108V108H0Z';
const rounded = 'M26,0H82C96.36,0 108,11.64 108,26V82C108,96.36 96.36,108 82,108H26C11.64,108 0,96.36 0,82V26C0,11.64 11.64,0 26,0Z';
const band = 'M32,63V53C32,40.85 41.85,31 54,31C66.15,31 76,40.85 76,53V63';
const pads = 'M35,53.5C38.59,53.5 41.5,56.41 41.5,60V72C41.5,75.59 38.59,78.5 35,78.5C31.41,78.5 28.5,75.59 28.5,72V60C28.5,56.41 31.41,53.5 35,53.5Z M73,53.5C76.59,53.5 79.5,56.41 79.5,60V72C79.5,75.59 76.59,78.5 73,78.5C69.41,78.5 66.5,75.59 66.5,72V60C66.5,56.41 69.41,53.5 73,53.5Z';
const gradients = [
 {type:'linear', start:[12,0], end:[88,108], stops:[[0,'#6648E9'],[.36,'#AC32D0'],[.7,'#EE3293'],[1,'#FA5065']]},
 {type:'radial', center:[12,117], radius:112, stops:[[0,'#FFD65A'],[.24,'#FFC14A'],[.46,'#FF9250'],[.69,'#FF5C62'],[1,'#FF5C62',0]]},
 {type:'radial', center:[109,-14], radius:103, stops:[[0,'#5148EC'],[.36,'#6544E7',.85],[1,'#6544E7',0]]},
];
const write=(file,text)=>{const dest=path.join(root,file);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.writeFileSync(dest,text+'\n');};
const vector=body=>`<vector ${ns} ${aapt} android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n${body}\n</vector>`;
const symbol=`    <path android:fillColor="@android:color/transparent" android:strokeColor="#FFFFFF" android:strokeWidth="6.2" android:strokeLineCap="round" android:pathData="${band}"/>\n    <path android:fillColor="#FFFFFF" android:pathData="${pads}"/>`;
const androidColor=([,color,opacity=1])=>opacity===1?color:'#'+Math.round(opacity*255).toString(16).padStart(2,'0')+color.slice(1);
const background=gradients.map(g=>{
 const position=g.type==='linear'?`android:startX="${g.start[0]}" android:startY="${g.start[1]}" android:endX="${g.end[0]}" android:endY="${g.end[1]}"`:`android:centerX="${g.center[0]}" android:centerY="${g.center[1]}" android:gradientRadius="${g.radius}"`;
 return `    <path android:pathData="${square}"><aapt:attr name="android:fillColor"><gradient android:type="${g.type}" ${position}>${g.stops.map(s=>`<item android:offset="${s[0]}" android:color="${androidColor(s)}"/>`).join('')}</gradient></aapt:attr></path>`;
}).join('\n');
write('app/src/main/res/drawable/ic_launcher_background.xml',vector(background));
write('app/src/main/res/drawable/ic_launcher_foreground.xml',vector(symbol));
write('app/src/main/res/drawable/ic_launcher.xml',vector(`  <group><clip-path android:pathData="${rounded}"/>\n${background}\n    <group android:pivotX="54" android:pivotY="54" android:scaleX="1.4" android:scaleY="1.4">\n${symbol}\n    </group>\n  </group>`));
for(const api of [26,33])write(`app/src/main/res/mipmap-anydpi-v${api}/ic_launcher.xml`,`<adaptive-icon ${ns}>\n    <background android:drawable="@drawable/ic_launcher_background"/>\n    <foreground android:drawable="@drawable/ic_launcher_foreground"/>${api===33?'\n    <monochrome android:drawable="@drawable/ic_launcher_foreground"/>':''}\n</adaptive-icon>`);
const defs=gradients.map((g,i)=>{
 const linear=g.type==='linear';
 const attrs=linear?`x1="${g.start[0]}" y1="${g.start[1]}" x2="${g.end[0]}" y2="${g.end[1]}"`:`cx="${g.center[0]}" cy="${g.center[1]}" r="${g.radius}"`;
 const tag=linear?'linearGradient':'radialGradient';
 return `<${tag} id="g${i}" gradientUnits="userSpaceOnUse" ${attrs}>${g.stops.map(([o,c,a=1])=>`<stop offset="${o}" stop-color="${c}" stop-opacity="${a}"/>`).join('')}</${tag}>`;
}).join('');
const svgSymbol=`<path d="${band}" fill="none" stroke="currentColor" stroke-width="6.2" stroke-linecap="round"/><path d="${pads}" fill="currentColor"/>`;
const svg=(body,extra='')=>`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" role="img" aria-label="MIMI 耳边" ${extra}>${body}</svg>`;
const svgBg=gradients.map((g,i)=>`<path d="${square}" fill="url(#g${i})"/>`).join('');
write('branding/mimi-icon.svg',svg(`<defs>${defs}<clipPath id="mask"><path d="${rounded}"/></clipPath></defs><g clip-path="url(#mask)">${svgBg}<g transform="translate(54 54) scale(1.4) translate(-54 -54)" color="white">${svgSymbol}</g></g>`));
write('branding/mimi-icon-adaptive.svg',svg(`<defs>${defs}</defs>${svgBg}<g color="white">${svgSymbol}</g>`));
write('branding/mimi-icon-monochrome.svg',svg(`<g color="currentColor">${svgSymbol}</g>`));
console.log('Generated 5 Android icon resources and 3 SVG masters.');
