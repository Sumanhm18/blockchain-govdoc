/* Real local Keycloak authorization code + S256 flow. Never record tokens, traces or login screenshots. */
const {chromium}=require('../application/web-assets/node_modules/playwright');
const fs=require('node:fs');const path=require('node:path');const crypto=require('node:crypto');const https=require('node:https');const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),tls=path.join(root,'.runtime/staging/tls');
const secrets=JSON.parse(fs.readFileSync(path.join(root,'.runtime/staging/private/credentials.json')));
const ca=fs.readFileSync(path.join(tls,'ca.pem'));
function api(url,method='GET',data=null,token=null,type='application/json'){
 return new Promise((resolve,reject)=>{const req=https.request(url,{method,ca,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(data?{'Content-Type':type}:{})}},res=>{const parts=[];res.on('data',x=>parts.push(x));res.on('end',()=>{const body=Buffer.concat(parts);resolve({status:res.statusCode,body,json:()=>JSON.parse(body)});});});req.on('error',()=>reject(new Error('TLS request failed')));if(data)req.write(data);req.end();});
}
(async()=>{
 let stage='start'; let browser;
 try {
  const pins=['api','identity'].map(name=>{const cert=new crypto.X509Certificate(fs.readFileSync(path.join(tls,name+'.pem')));return crypto.createHash('sha256').update(cert.publicKey.export({type:'spki',format:'der'})).digest('base64');});
  browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE}:{}),args:['--host-resolver-rules=MAP identity.govdoc.local 127.0.0.1','--ignore-certificate-errors-spki-list='+pins.join(',')]});
  const context=await browser.newContext(); const page=await context.newPage();
  stage='synthetic ownership fixture';
  const form=new URLSearchParams({grant_type:'password',client_id:'govdoc-lab-test',client_secret:secrets.clientSecret,username:'issuer',password:secrets.accounts.issuer.password});
  const issuer=(await api('https://localhost:18443/realms/govdoc-lab/protocol/openid-connect/token','POST',form.toString(),null,'application/x-www-form-urlencoded')).json().access_token;
  assert.ok(issuer);
  const id='BROWSER-'+crypto.randomBytes(6).toString('hex').toUpperCase();
  async function login(user,linked=''){
   await page.goto('https://localhost:18444/'+(linked?'#document='+linked:''));await page.locator('#identity-login').waitFor({state:'visible',timeout:20000});
   await page.locator('#identity-login').click();await page.waitForURL(/identity\.govdoc\.local/);
   assert.equal(new URL(page.url()).searchParams.get('code_challenge_method'),'S256');
   await page.locator('#username').fill(user);await page.locator('#password').fill(secrets.accounts[user].password);await page.locator('#kc-login').click();
   await page.waitForURL('https://localhost:18444/**');await page.locator('#workspace').waitFor({state:'visible',timeout:30000});
   assert.equal(new URL(page.url()).searchParams.has('code'),false);
  }
  stage='citizen assignment reference';await login('citizen');
  const citizenReference=await page.locator('#citizen-reference').inputValue();
  assert.equal(citizenReference,secrets.accounts.citizen.id);
  await page.locator('#logout').click();await page.locator('#connection').waitFor({state:'visible'});
  assert.equal(await page.locator('#citizen-reference').inputValue(),'');
  stage='issuer uploads for citizen';await login('issuer');
  assert.equal(await page.locator('#citizen-account').isVisible(),false);
  await page.locator('#issue-tab').click();await page.locator('[name=docId]').fill(id);
  await page.locator('[name=ownerSubject]').fill(citizenReference);
  const file={name:'citizen.pdf',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.4\nSynthetic citizen browser document\n%%EOF\n')};
  await page.locator('[name=file]').setInputFiles(file);await page.locator('#upload button').click();
  await page.locator('#upload-result').filter({hasText:'CONFIRMED'}).waitFor({timeout:45000});
  await page.locator('#logout').click();await page.locator('#connection').waitFor({state:'visible'});
  stage='citizen code flow';await login('citizen');
  assert.match(await page.locator('#roles').textContent(),/CITIZEN/);assert.equal(await page.locator('#issue-tab').isVisible(),false);
  await page.locator('#summary-activity').filter({hasText:id}).waitFor();
  stage='owned dashboard search';await page.locator('#search-prefix').fill(id);await page.locator('#search button').click();await page.locator('#search-results').filter({hasText:id}).waitFor();
  await page.locator('#search-results button').click();await page.locator('#record-id').filter({hasText:id}).waitFor();assert.equal(await page.locator('#history').isVisible(),false);
  stage='citizen exact download and verification';
  const downloading=page.waitForEvent('download');await page.locator('#download').click();
  const downloaded=await downloading;assert.deepEqual(fs.readFileSync(await downloaded.path()),file.buffer);
  await page.locator('#verify-file').setInputFiles(file);await page.locator('#verify button').click();
  await page.locator('#verification').filter({hasText:'VALID'}).waitFor();
  stage='QR link preserves access';await page.locator('#share').click();assert.equal(await page.locator('#share-link').inputValue(),'https://localhost:18444/#document='+id);
  stage='provider logout';await page.locator('#logout').click();await page.waitForURL('https://localhost:18444/**');await page.locator('#connection').waitFor({state:'visible'});
  stage='other citizen QR sign-in';await login('other-citizen',id);await page.locator('#notice').filter({hasText:'404'}).waitFor();
  assert.equal(await page.locator('#workspace').isVisible(),true);
  await page.locator('#dashboard-tab').click();await page.locator('#search-prefix').fill(id);await page.locator('#search button').click();await page.locator('#search-results').filter({hasText:'No accessible documents'}).waitFor();
  assert.equal(await page.locator('#record').isVisible(),false);
  await page.locator('#summary-note').filter({hasText:'All accessible records'}).waitFor();
  assert.equal((await page.locator('#summary-activity').textContent()).includes(id),false);
  const stored=await page.evaluate(()=>Object.values(localStorage).concat(Object.values(sessionStorage)));
  assert.ok(stored.every(value=>!value.includes('access_token')&&!value.includes('refresh_token')));
  await page.locator('#logout').click();
  assert.equal((await api('https://localhost:18444/api/documents/'+id+'/revoke','POST',JSON.stringify({reason:'Synthetic browser identity drill complete'}),issuer)).status,200);
  console.log('PASS: real PKCE citizen sign-in, citizen reference assignment, issuer browser upload, citizen exact download/verification, owned search, cross-citizen denial, QR link authorization, callback cleanup and provider logout. TLS trusts only the local fixture public keys.');
 } catch(error){console.error('FAIL: OIDC browser stage '+stage+' ('+error.name+'). Private values omitted.');process.exitCode=1;}
 finally {if(browser)await browser.close();}
})();
