import {test,expect} from '@playwright/test';
const user={authenticated:true,selectedSeat:{playerName:'neighbor',playerRoles:['audience']}};
async function mock(page, session={authenticated:false}) {
 let current=structuredClone(session), referral=null, events=[];
 await page.route('**/auth.json',r=>r.fulfill({json:{auth_base_url:'https://auth.intergraph.ai'}}));
 await page.route('**/session',r=>r.fulfill({json:current}));
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
