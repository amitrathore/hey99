# Hey99

A recruiting preview for the third Community.Markets launch, focused on Manteca, Lathrop, Ripon, Salida and Escalon. The visual direction follows the Hey108 and HeyLaguna family while giving Highway 99 its own route identity.

The site is static HTML and CSS. Open `index.html` directly or serve the directory with `python3 -m http.server 8000`. There is no build or account backend. The `/join/` page embeds a Hey99-specific Tally application; responses stay in the same Tally workspace as the sibling markets. The `/market/` page is a Hey99 marketplace preview without invented sellers or offers. The `/economics/` page explains merchant terms and contributor rewards within Hey99. Its content reference is the shared [Community.Markets earnings model](https://community.markets/economics/); keep rates and percentage bases consistent when updating either explanation. Ecosystem navigation appears only in the footer.

## GitHub Pages

In **Settings → Pages**, choose **Deploy from a branch**, `main`, and `/ (root)`. The custom domain is `https://hey99.online/`. The root `CNAME` file and Cloudflare DNS point this branch-published site to GitHub Pages. All pages use relative internal and asset paths, and canonical and social image URLs use the custom domain.

## Launch status

This is a preview. Hey99 offers, checkout and payouts are not active.
