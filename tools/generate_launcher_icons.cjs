#!/usr/bin/env node
// Native vector source plus reproducible fallback PNGs and review previews.
// Requires sharp (available in the Codex bundled Node runtime).
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const res = path.join(root, 'utility-tracker/app/src/main/res');
const art = path.join(root, 'utility-tracker/artwork');
const colors = {light: ['#EAF7FF', '#006E8A'], dark: ['#09252F', '#78D9EC']};
async function write(file, text) { await fs.mkdir(path.dirname(file), {recursive:true}); await fs.writeFile(file, text); }
(async () => {
  const source = await fs.readFile(path.join(art, 'launcher-mark.svg'), 'utf8');
  const data = source.match(/ d="([^"]+)"/)[1];
  const vector = color => `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    <path android:fillColor="${color}" android:fillType="evenOdd" android:pathData="${data}" />\n</vector>\n`;
  await write(path.join(res,'drawable/ic_launcher_foreground.xml'), vector('@color/launcher_foreground'));
  await write(path.join(res,'drawable/ic_launcher_monochrome.xml'), vector('#FF000000'));
  await write(path.join(res,'drawable/ic_launcher_background.xml'), '<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n    <solid android:color="@color/launcher_background" />\n</shape>\n');
  for (const mode of ['', '-night']) for (const name of ['ic_launcher','ic_launcher_round']) await write(path.join(res,`mipmap${mode}-anydpi/${name}.xml`), '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n');
  const svg = (bg, fg, shape='round', size=192) => `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="18 18 72 72"><defs><clipPath id="mask">${shape==='circle'?'<circle cx="54" cy="54" r="36"/>':'<rect x="18" y="18" width="72" height="72" rx="16"/>'}</clipPath></defs><g clip-path="url(#mask)"><path fill="${bg}" d="M0 0H108V108H0Z"/><path fill="${fg}" fill-rule="evenodd" d="${data}"/></g></svg>`;
  for (const [mode,[bg,fg]] of Object.entries(colors)) {
    await write(path.join(res,`values${mode==='dark'?'-night':''}/launcher_colors.xml`), `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="launcher_background">${bg}</color>\n    <color name="launcher_foreground">${fg}</color>\n</resources>\n`);
    for (const [density,size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) for (const [name,shape] of [['ic_launcher','round'],['ic_launcher_round','circle']]) {
      const folder=path.join(res,`mipmap${mode==='dark'?'-night':''}-${density}`);
      await fs.mkdir(folder,{recursive:true});
      await sharp(Buffer.from(svg(bg,fg,shape,size*4))).resize(size,size).png().toFile(path.join(folder,`${name}.png`));
      await fs.rm(path.join(folder,`${name}.webp`),{force:true});
    }
    await write(path.join(art,`launcher-${mode}.svg`),svg(bg,fg));
  }
  // A single portable preview: masks, themed monochrome, and real launcher sizes.
  const tiles=[];
  let labels='';
  for (const [row,mode] of ['light','dark','monochrome'].entries()) {
    const [bg,fg]=mode==='monochrome'?['#E8DEF8','#1D192B']:colors[mode];
    for (const [col,shape] of ['round','circle'].entries()) tiles.push({input:await sharp(Buffer.from(svg(bg,fg,shape,144))).png().toBuffer(),left:150+col*196,top:32+row*190});
    for (const [col,size] of [48,32,24].entries()) tiles.push({input:await sharp(Buffer.from(svg(bg,fg,'round',size*4))).resize(size,size).png().toBuffer(),left:566+col*82,top:70+row*190});
    labels+=`<text x="24" y="${105+row*190}" fill="${row===1?'#EAF7FF':'#173942'}" font-size="19">${mode}</text>`;
  }
  const panel=`<svg xmlns="http://www.w3.org/2000/svg" width="824" height="604"><path fill="#F4F8FA" d="M0 0H824V604H0Z"/><path fill="#162830" d="M0 214H824V404H0Z"/>${labels}<text x="559" y="582" fill="#52666F" font-size="14">48px · 32px · 24px</text></svg>`;
  await sharp(Buffer.from(panel)).composite(tiles).png().toFile(path.join(art,'launcher-preview.png'));
})();
