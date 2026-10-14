import {test,expect} from '@playwright/test';
const user={authenticated:true,selectedSeat:{playerName:'neighbor',playerRoles:['audience']}};
async function mock(page, session={authenticated:false}) {
 let current=structuredClone(session), referral=null, events=[];
 await page.route('**/auth.json',r=>r.fulfill({json:{auth_base_url:'https://auth.intergraph.ai'}}));
 await page.route('**/session',r=>r.fulfill({json:current}));
 await page.route('**/seats',r=>r.fulfill({json:{seats:current.selectedSeat?[current.selectedSeat]:[]}}));
 await page.route('**/coseller/claim-visitor',r=>r.fulfill({json:{ok:true}}));
 await page.route('**/join/claim',r=>{const data=r.request().postDataJSON();current={authenticated:true,selectedSeat:{playerName:data['player-name'],playerRoles:['audience']}};return r.fulfill({json:{success:true}})});
 await page.route('**/api/coseller/activate',r=>{current.selectedSeat.playerRoles.push('coseller');referral='neighbor-token';return r.fulfill({json:{status:'active','ref-token':referral}})});
 await page.route('**/api/coseller/me',r=>r.fulfill({json:{'ref-token':referral||'neighbor-token'}}));
 await page.route('**/api/studio',r=>r.fulfill({json:{tasks:[{id:'draft-1',status:'drafting',assignee:'neighbor',spec:{title:'My community story'},'draft-content':{title:'My community story',body:'Something useful.'}}]}}));
 await page.route('**/events',r=>{events.push(r.request().postDataJSON());return r.fulfill({json:{success:true}})});
 return {events};
}
test('existing pages and new articles have mobile layouts and server metadata',async({page})=>{
 await mock(page);
 for(const width of [320,375,768,1440]){
  await page.setViewportSize({width,height:900});
  for(const path of ['/','/join/','/market/','/economics/','/stories/','/stories/a-recipe-can-start-a-journey/','/account/','/share/','/studio/']){
   await page.goto(path);await expect(page.locator('h1')).toHaveCount(1);
   expect(await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth)).toBeLessThanOrEqual(0);
  }
 }
 await page.goto('/stories/a-recipe-can-start-a-journey/');
 await expect(page).toHaveTitle('Hey99 — A recipe can start a journey.');
 await expect(page.locator('meta[property="og:title"]')).toHaveAttribute('content','Hey99 — A recipe can start a journey.');
 await expect(page.getByText('By maya',{exact:false})).toBeVisible();
 await expect(page.getByRole('button',{name:'Share this story'})).toBeVisible();
});
test('join form preserves mobile dynamic height',async({page})=>{
 await mock(page);await page.goto('/join/');
 await expect(page.locator('iframe')).toHaveAttribute('data-tally-src',/dynamicHeight=1/);
});
test('a signed-in visitor chooses a handle then activates a personal sharing link',async({page})=>{
 await mock(page,{authenticated:true});await page.goto('/share/');
 await page.getByLabel('Your handle').fill('neighbor');await page.getByRole('button',{name:'Claim my handle'}).click();
 await page.getByRole('button',{name:'Set up my sharing link'}).click();
 await expect(page.getByLabel('Your personal link')).toHaveValue(/ref=neighbor-token/);
 await expect(page.getByText('Preview activity does not create earnings.')).toBeVisible();
});
test('referral visit records the exact article and shares use the current coseller token',async({page})=>{
 await mock(page,{...user,selectedSeat:{playerName:'neighbor',playerRoles:['audience','coseller']}});
 let touch;await page.route('**/public/touch',r=>{touch=r.request().postDataJSON();return r.fulfill({json:{ok:true}})});
 await page.addInitScript(()=>{Object.defineProperty(navigator,'clipboard',{value:{writeText:async v=>window.copied=v}})});
 await page.goto('/stories/a-recipe-can-start-a-journey/?ref=old-token');
 await expect.poll(()=>touch).toEqual({token:'old-token',path:'/stories/a-recipe-can-start-a-journey/'});
 await page.getByRole('button',{name:'Share this story'}).click();
 await expect.poll(()=>page.evaluate(()=>window.copied)).toContain('ref=neighbor-token');
 expect(await page.evaluate(()=>window.copied)).not.toContain('old-token');
});
test('writer submits a story; audience does not see editorial controls',async({page})=>{
 const flow=await mock(page,{...user,selectedSeat:{playerName:'neighbor',playerRoles:['contributor']}});
 await page.goto('/studio/');await page.locator('.draft-form textarea[name=body]').fill('My useful local story.');
 await page.getByRole('button',{name:'Submit for review'}).click();
 await expect.poll(()=>flow.events.length).toBe(1);
 expect(flow.events[0]['event/type']).toBe('attention/submit-draft');
 expect(flow.events[0]['event/data'].content.body).toBe('My useful local story.');
 await expect(page.getByRole('button',{name:'Approve and publish'})).toHaveCount(0);
});

test('invited writer can generate and copy a one-hour seat token',async({page})=>{
 await mock(page,{...user,selectedSeat:{playerName:'neighbor',playerRoles:['contributor']}});
 await page.route('**/api/player-token',r=>r.fulfill({json:{token:'test-seat-token','expires-at':Date.now()+3600000,'expires-in':3600}}));
 await page.addInitScript(()=>{Object.defineProperty(navigator,'clipboard',{value:{writeText:async v=>window.copied=v}})});
 await page.setViewportSize({width:320,height:900});
 await page.goto('/studio/');
 await expect(page.locator('.connect-ai > summary')).toBeVisible();
 expect(await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth)).toBeLessThanOrEqual(0);
 expect(await page.locator('.studio-tasks').evaluate((tasks)=>tasks.compareDocumentPosition(document.querySelector('.connect-ai')) & Node.DOCUMENT_POSITION_FOLLOWING)).toBeTruthy();
 await expect(page.getByRole('button',{name:'Generate seat token'})).toBeHidden();
 await page.locator('.connect-ai > summary').click();
 await page.getByRole('button',{name:'Generate seat token'}).click();
 await expect(page.locator('.connect-ai')).toHaveAttribute('open','');
 await expect(page.getByLabel('Seat token')).toHaveAttribute('type','password');
 await page.getByRole('button',{name:'Reveal token'}).click();
 await expect(page.getByLabel('Seat token')).toHaveValue('test-seat-token');
 await page.getByRole('button',{name:'Copy token'}).click();
 await expect.poll(()=>page.evaluate(()=>window.copied)).toBe('test-seat-token');
 await page.getByText('Set up Codex').click();
 await expect(page.getByText('HEY99_SEAT_TOKEN',{exact:false}).first()).toBeVisible();
 await page.goto('/account/');
 await page.goto('/studio/');
 await expect(page.getByLabel('Seat token')).toHaveCount(0);
});

test('multi-seat account selects an invited seat before generating a token',async({page})=>{
 const reader={playerName:'reader',playerRoles:['audience']};
 const writer={playerName:'writer',playerRoles:['contributor']};
 await mock(page,{authenticated:true,selectedSeat:null});
 await page.route('**/session',r=>r.fulfill({json:{authenticated:true,selectedSeat:r.request().headers()['x-selected-seat']==='writer'?writer:r.request().headers()['x-selected-seat']==='reader'?reader:null}}));
 await page.route('**/seats',r=>r.fulfill({json:{seats:[reader,writer]}}));
 let tokenSeat;
 await page.route('**/api/player-token',r=>{tokenSeat=r.request().headers()['x-selected-seat'];return r.fulfill({json:{token:'writer-token','expires-at':Date.now()+3600000,'expires-in':3600}})});
 await page.goto('/studio/');
 await expect(page.getByText('Choose the Hey99 handle you are acting as.')).toBeVisible();
 await page.getByLabel('Acting as').selectOption('writer');
 await expect(page.locator('.connect-ai > summary')).toBeVisible();
 await page.locator('.connect-ai > summary').click();
 await page.getByRole('button',{name:'Generate seat token'}).click();
 expect(tokenSeat).toBe('writer');
 await page.getByLabel('Acting as').selectOption('reader');
 await expect(page.locator('.connect-ai')).toHaveCount(0);
 await expect(page.getByLabel('Seat token')).toHaveCount(0);
});

test('audience seat does not see AI connection controls',async({page})=>{
 await mock(page,user);
 await page.goto('/studio/');
 await expect(page.locator('.connect-ai')).toHaveCount(0);
});

test('seat token disappears when it expires',async({page})=>{
 await mock(page,{...user,selectedSeat:{playerName:'neighbor',playerRoles:['contributor']}});
 await page.route('**/api/player-token',r=>r.fulfill({json:{token:'short-lived-token','expires-at':Date.now()+1000,'expires-in':1}}));
 await page.goto('/studio/');
 await page.locator('.connect-ai > summary').click();
 await page.getByRole('button',{name:'Generate seat token'}).click();
 await expect(page.getByLabel('Seat token')).toBeVisible();
 await expect(page.getByLabel('Seat token')).toHaveCount(0,{timeout:5000});
});

test('writer previews and submits a featured image and formatted story',async({page})=>{
 const flow=await mock(page,{...user,selectedSeat:{playerName:'neighbor',playerRoles:['contributor']}});
 let preview;
 await page.route('**/api/studio/preview',r=>{preview=r.request().postDataJSON();return r.fulfill({json:{html:'<h2>A community story</h2><figure><img src="/assets/editorial/finding-home-ripon-v1.webp" alt="Ripon" loading="lazy"><figcaption>Neighbors together.</figcaption></figure>'}})});
 await page.setViewportSize({width:320,height:900});await page.goto('/studio/');
 await page.getByLabel('Featured image URL').fill('/assets/featured/why-start-hey99-v1.jpg');
 await page.getByLabel('Featured image description').fill('Neighbors and shops.');
 await page.getByLabel('Story format').selectOption('html');
 await page.locator('.draft-form textarea[name=body]').fill('<h2>A community story</h2><p>Our place.</p>');
 await page.getByRole('button',{name:'Preview story'}).click();
 await expect(page.locator('.story-preview figcaption')).toHaveText('Neighbors together.');
 expect(preview.metadata.body_format).toBe('html');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth)).toBeLessThanOrEqual(0);
 await page.getByRole('button',{name:'Submit for review'}).click();
 await expect.poll(()=>flow.events.length).toBe(1);
 expect(flow.events[0]['event/data'].content.featured_image).toBe('/assets/featured/why-start-hey99-v1.jpg');
 expect(flow.events[0]['event/data'].content.metadata.featured_image_alt).toBe('Neighbors and shops.');
});


test('illustrated story images fit mobile screens and load with sharing metadata',async({page},testInfo)=>{
 await mock(page);
 for(const width of [320,375,1440]){
  await page.setViewportSize({width,height:900});await page.goto('/stories/illustrated-community/');
  const images=page.locator('.article-body img');await expect(images).toHaveCount(5);
  for(const image of await images.all()){
   await image.scrollIntoViewIfNeeded();
   await expect.poll(()=>image.evaluate(i=>i.complete&&i.naturalWidth>0)).toBe(true);
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth)).toBeLessThanOrEqual(0);
 }
 await expect(page.locator('.story-featured')).toHaveAttribute('loading','eager');
 await expect(page.locator('.article-body figure img').first()).toHaveAttribute('loading','lazy');
 await expect(page.locator('meta[property="og:image"]')).toHaveAttribute('content','https://hey99.online/assets/featured/why-start-hey99-v1.jpg');
 await page.setViewportSize({width:375,height:900});await page.screenshot({path:testInfo.outputPath('illustrated-mobile.png'),fullPage:true});
});

test('founder page is linked from every footer and fits mobile screens',async({page})=>{
 await mock(page);
 for(const path of ['/','/join/','/market/','/economics/','/stories/','/account/','/studio/']){
  await page.goto(path);await expect(page.locator('footer a[href="/about/"]')).toHaveCount(1);
 }
 for(const width of [320,375,1440]){
  await page.setViewportSize({width,height:900});await page.goto('/about/');
  await expect(page.locator('h1')).toHaveText('Hi, I’m Amit.');
  await expect(page.getByText('everyday people like us',{exact:false})).toBeVisible();
  const portrait=page.locator('.founder-portrait img');await expect.poll(()=>portrait.evaluate(i=>i.complete&&i.naturalWidth>0)).toBe(true);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth-innerWidth)).toBeLessThanOrEqual(0);
 }
 await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href','https://hey99.online/about/');
 await expect(page.locator('meta[property="og:description"]')).toHaveAttribute('content',/Meet Amit Rathore/);
 await page.goto('/about');await expect(page).toHaveURL(/\/about\/$/);
});

test('income page explains earning paths and is discoverable across the site',async({page},testInfo)=>{
 await mock(page);
 for(const path of ['/','/join/','/market/','/economics/','/about/','/stories/','/privacy/','/account/']){
  await page.goto(path);
  await expect(page.locator('footer a[href="/income/"]')).toHaveCount(1);
  await expect(page.locator('footer a[href="/economics/"]')).toHaveText('Rewards');
  await expect(page.getByRole('link',{name:'How rewards work',exact:true})).toHaveCount(0);
 }
 for(const width of [320,375,768,1440]){
  await page.setViewportSize({width,height:900});await page.goto('/income/');
  await expect(page.locator('h1')).toHaveText('Help your community grow.Share in what it creates.');
  await expect(page.locator('.income-cards article')).toHaveCount(4);
  await expect(page.locator('.income-cards')).toContainText('you passively earn $50');
  await expect(page.locator('.income-cards')).toContainText('you earn $100');
  await expect(page.locator('main')).toContainText('preview activity does not create earnings');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth)).toBe(false);
 }
 await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href','https://hey99.online/income/');
 await expect(page.locator('meta[property="og:description"]')).toHaveAttribute('content',/active and passive side income/);
 await expect(page.locator('meta[property="og:image"]')).toHaveAttribute('content','https://hey99.online/assets/og-card-v3.png');
 await page.goto('/income?ref=neighbor');await expect(page).toHaveURL(/\/income\/\?ref=neighbor$/);
 await page.locator('footer').screenshot({path:testInfo.outputPath('income-footer.png')});
 await page.screenshot({path:testInfo.outputPath('income-desktop.png'),fullPage:true});
 await page.setViewportSize({width:375,height:900});await page.screenshot({path:testInfo.outputPath('income-mobile.png'),fullPage:true});
});
