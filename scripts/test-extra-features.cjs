/* Live local expiry, private dashboard and batch verification; no credentials in output. */
const {chromium}=require('../application/web-assets/node_modules/playwright');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
(async()=>{
 const root=path.resolve(__dirname,'..'),creds=JSON.parse(fs.readFileSync(path.join(root,'.runtime/secrets/credentials.json')));
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE}:{})});
 let stage='start';
 try {
  const context=await browser.newContext({viewport:{width:1280,height:900}}),page=await context.newPage(),base='http://127.0.0.1:18081';
  const headers={Authorization:'Basic '+Buffer.from('issuer:'+creds.issuer).toString('base64')};
  const before=await (await context.request.get(base+'/api/documents/summary',{headers})).json();
  const prefix='EXTRA-'+crypto.randomBytes(4).toString('hex').toUpperCase();
  const ids=[prefix+'-ACTIVE',prefix+'-EXPIRED',prefix+'-REVOKED'];
  const file={name:'synthetic.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\nSynthetic extra feature fixture\n%%EOF\n')};
  const text=async(selector,value)=>page.locator(selector).filter({hasText:value}).waitFor({timeout:45000});
  await page.goto(base);await page.locator('#username').fill('issuer');await page.locator('#password').fill(creds.issuer);await page.locator('#login button').click();await page.locator('#workspace').waitFor();
  stage='expiry upload fields';await page.locator('#issue-tab').click();
  for(let i=0;i<ids.length;i++){
   await page.locator('[name=docId]').fill(ids[i]);await page.locator('[name=expiresAt]').fill(i===1?'2020-01-01T00:00':'2099-01-01T00:00');
   await page.locator('[name=file]').setInputFiles(file);await page.locator('#upload button').click();await text('#upload-result','CONFIRMED — '+ids[i]);
  }
  assert.equal((await context.request.post(base+'/api/documents/'+ids[2]+'/revoke',{headers,data:{reason:'Synthetic feature test'}})).status(),200);
  stage='expired read and download';
  await page.locator('[data-panel=lookup]').click();await page.locator('#lookup-id').fill(ids[1]);await page.locator('#find button').click();await text('#record-state','EXPIRED');
  assert.equal(await page.locator('#download').isEnabled(),false);
  assert.equal((await context.request.get(base+'/api/documents/'+ids[1]+'/file',{headers})).status(),409);
  await page.locator('#verify-file').setInputFiles(file);await page.locator('#verify button').click();await text('#verification','EXPIRED');
  stage='dashboard totals and filter';
  const after=await (await context.request.get(base+'/api/documents/summary',{headers})).json();
  assert.equal(after.complete,true);assert.equal(after.counts.total,before.counts.total+3);
  for(const key of ['active','expired','revoked'])assert.equal(after.counts[key],before.counts[key]+1);
  await page.locator('#dashboard-tab').click();await page.locator('#refresh-summary button').click();await text('#summary-note','All accessible records');
  assert.equal(await page.locator('#summary-counts dd').count(),4);await text('#summary-activity',ids[2]);
  await page.locator('#search-prefix').fill(prefix);await page.locator('#search-status').selectOption('EXPIRED');await page.locator('#search button').click();await text('#search-results',ids[1]);
  assert.equal(await page.locator('#search-results button').count(),1);
  stage='mixed batch verification';await page.locator('#batch-tab').click();
  const files=[{...file,name:ids[0]+'.pdf'},{...file,name:'tampered.pdf',buffer:Buffer.from('%PDF-1.4\nChanged fixture\n%%EOF\n')},{...file,name:ids[1]+'.pdf'},{...file,name:ids[2]+'.pdf'},{...file,name:prefix+'-MISSING.pdf'}];
  await page.locator('#batch-files').setInputFiles(files);await page.locator('#batch-rows input').nth(1).fill(ids[0]);await page.locator('#batch-verify button[type=submit]').click();await text('#batch-progress','5 of 5');
  const results=await page.locator('#batch-rows p').allTextContents();assert.deepEqual(results.slice(0,4),['VALID','TAMPERED_OR_MISMATCH','EXPIRED','REVOKED']);assert.match(results[4],/404/);
  stage='cancel remaining mocked batch';let seen,release,count=0;const started=new Promise(r=>seen=r),held=new Promise(r=>release=r);
  await page.route('**/verify-file',async route=>{count++;seen();await held;await route.fulfill({status:200,contentType:'application/json',body:'{"status":"VALID"}'});});
  await page.locator('#batch-files').setInputFiles([{...file,name:ids[0]+'.pdf'},{...file,name:ids[0]+'.pdf'}]);await page.locator('#batch-verify button[type=submit]').click();await started;await page.locator('#batch-cancel').click();release();
  await page.waitForFunction(()=>document.getElementById('batch-verify').getAttribute('aria-busy')==='false');assert.equal(count,1);assert.equal(await page.locator('#batch-rows p').first().textContent(),'Not checked — cancelled');await page.unrouteAll();
  stage='mobile dashboard';await page.setViewportSize({width:390,height:844});await page.locator('#dashboard-tab').click();assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  fs.mkdirSync(path.join(root,'.runtime/browser-review'),{recursive:true});await page.screenshot({path:path.join(root,'.runtime/browser-review/extra-dashboard.png'),fullPage:true});
  await page.locator('#batch-tab').click();await page.screenshot({path:path.join(root,'.runtime/browser-review/extra-batch.png'),fullPage:true});
  await page.locator('#logout').click();assert.equal(await page.locator('#batch-rows').textContent(),'');assert.equal(await page.locator('#summary-counts').textContent(),'');
  assert.equal((await context.request.post(base+'/api/documents/'+ids[0]+'/revoke',{headers,data:{reason:'Synthetic feature test complete'}})).status(),200);
  console.log('PASS: live expiry upload/verification/download denial, private dashboard totals and expiry filter, mixed batch verification, sign-out clearing and mobile layout. Separate intercepted responses verify batch cancellation.');
 }catch(error){console.error('FAIL: extra features stage '+stage+' ('+error.name+'); private values omitted.');process.exitCode=1;}
 finally{await browser.close();}
})();
