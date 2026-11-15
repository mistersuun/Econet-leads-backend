# Putting the CRM online (step by step)

You will set up three free services: **Supabase** (database), **Render** (the CRM server) and **Netlify** (the CRM web page, already done by the frontend). Keep a notepad open: you will copy several values.

## 1. Database (Supabase)

1. Go to supabase.com, sign in, click **New project** (name: `econet-crm`). Choose a region close to Montréal (for example *East US*). Write down the **database password** you choose.
2. Click **Connect** at the top of the project, then **Session pooler**. Copy the connection string. It looks like:
   `postgresql://postgres.abcdefghijk:[YOUR-PASSWORD]@aws-0-us-east-1.pooler.supabase.com:5432/postgres`
3. Turn it into the three Render values:
   - **DB_URL** = `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require` (same host as above, start with `jdbc:postgresql://`, no user or password inside, add `?sslmode=require` at the end)
   - **DB_USERNAME** = `postgres.<project-ref>` (the part before the `:` in the string, for example `postgres.abcdefghijk`)
   - **DB_PASSWORD** = the database password from step 1

The tables are created automatically the first time the server starts.

## 2. The server (Render)

1. Generate two secrets (they protect logins). With a terminal, run this twice: `openssl rand -hex 32`. Without a terminal: use any online "random hex string generator" and ask for **64 characters**, hexadecimal. The two secrets must be **different**.
2. Choose a password for the first admin account (**ADMIN_INITIAL_PASSWORD**, at least 12 characters).
3. On render.com: **New → Blueprint**, connect your GitHub account, pick this repository. Render reads `render.yaml` and asks for the values:
   - DB_URL, DB_USERNAME, DB_PASSWORD (step 1)
   - JWT_SECRET and JWT_REFRESH_SECRET (the two secrets)
   - ADMIN_INITIAL_PASSWORD
   - CORS_ALLOWED_ORIGINS (step 5)
   - The optional ones (e-mail, website): fill them in using steps 3 and 4, or leave them empty for now. If you fill SMTP_HOST, also fill SMTP_PORT (587).
4. Click **Apply**. The first build takes about 5 to 10 minutes. When it says **Live**, open `https://econet-leads-api.onrender.com/actuator/health`: you should see `{"status":"UP"}`. (If the name was taken, Render adds letters to the address; use the one shown on the service page.)
5. Any later change you push to the `master` branch redeploys automatically. To change a value later: Render → your service → **Environment**.

## 3. Sending e-mails (Gmail), optional

1. Use a Google account with **2-step verification** turned on.
2. Go to myaccount.google.com/apppasswords, create an app password named `CRM`, copy the 16 letters.
3. In Render set: SMTP_HOST = `smtp.gmail.com`, SMTP_PORT = `587`, SMTP_USERNAME = your Gmail address, SMTP_PASSWORD = the 16 letters (no spaces), MAIL_FROM = your Gmail address.

Nothing is sent while SMTP_HOST is empty.

## 4. Website requests, optional

The CRM can pull quote and contact requests from the website's own Supabase project. It only **reads**; it never writes there.

1. Open the **website's** Supabase project → **Connect → Session pooler**.
2. Fill WEBSITE_DB_URL (`jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`), WEBSITE_DB_USERNAME (`postgres.<project-ref>`) and WEBSITE_DB_PASSWORD, the same way as in step 1. A dedicated read-only database user is safer if you know how to create one. The CRM also imports the website's optional `estimate_requests` table (visitors who asked to receive their estimate by email) once `supabase/estimate-requests.sql` has been run on the website's database; until then that table is skipped. If you use a dedicated read-only user, grant it `SELECT` on `estimate_requests` too.

## 5. Linking the web page (CORS)

Set **CORS_ALLOWED_ORIGINS** to the CRM's Netlify address, for example `https://econet-crm.netlify.app` (no slash at the end, no path). Several addresses can be separated by commas. In Netlify, the frontend must point to the Render address from step 2.

## 6. First use

1. Open the CRM and log in as **admin** with ADMIN_INITIAL_PASSWORD (if you left it empty, the server logs a generated password: Render → **Logs**, search "ADMIN_INITIAL_PASSWORD"). Change it right away.
2. Create an account for each caller: **Réglages → Équipe → Ajouter un membre** (username, e-mail, password, role USER). The same screen sets each caller's daily targets.
3. Run imports from the Sources / Imports screen (for example "Site web – demandes"). Each run is logged.
4. **A failed import** shows an error message in the import log. Common causes: wrong database value (WEBSITE_DB_*), the other database is unreachable or the password changed, or the file has the wrong columns. Fix the value in Render → Environment (the server restarts) and run the import again. Nothing is lost; imports can be repeated safely.

## 7. Free plan: sleeping

On the free plan the server **falls asleep after 15 minutes without visits**. The next visit takes about 30 to 60 seconds to wake it, and scheduled imports do not run while it sleeps. To avoid that, in Render open the service → **Settings → Instance Type** and choose a paid plan (Starter); no redeploy of code is needed. Note the free Supabase database also pauses after a week with no activity: use **Restore** in Supabase if that happens.

### Keeping the server awake during the day (free plan)

The workflow `.github/workflows/keep-awake.yml` calls `/actuator/health` every 10 minutes, Monday to Friday, 12:00 to 23:59 UTC (08:00 to 19:59 in Montréal in summer), so callers rarely wait for a cold start. You can also run it by hand: GitHub → **Actions → keep-awake → Run workflow**.

- GitHub **disables scheduled workflows after 60 days without any activity** in the repository. If the pings stop, open Actions and click **Enable workflow** (a push or a manual run also resets the counter).
- Alternative that does not depend on GitHub: create a free job at cron-job.org that calls `https://econet-leads-api.onrender.com/actuator/health` every 10 minutes during your hours.
- If your Render address differs (step 2), edit the URL in the workflow file.

## 8. Replace the old secrets (important)

Older JWT secrets were committed in the project history, so treat them as public.

1. Generate two new secrets as in step 2.
2. In Render → Environment, replace JWT_SECRET and JWT_REFRESH_SECRET, then **Save**. The server restarts.
3. Everyone is logged out once and must log in again. That is expected, and it makes the old secrets useless.
4. Never reuse the old values anywhere.

## 9. Optional settings (environment variables)

All of these are optional. When one is missing, the matching feature shows "non configuré" in the CRM and the server still starts. Set them in Render → your service → **Environment**.

| Variable | What it does | Where to get it |
|---|---|---|
| APP_PUBLIC_URL | Public address of this server, used in links sent to customers (quotes, booking). | The Render address, e.g. `https://econet-leads-api.onrender.com` (no slash at the end). |
| CRM_URL | Address of the CRM, used in alerts and emails. | The Netlify address, e.g. `https://econet-leads.netlify.app`. |
| TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM | Text messages (owner alerts, follow-ups). All three are needed. | twilio.com console: Account SID and Auth Token on the dashboard; TWILIO_FROM is your Twilio number in international format, e.g. `+15145550123`. |
| ALERT_PHONE | Who receives a text for each new website request. | Your cell number(s) in international format, separated by commas. |
| ALERT_EMAIL | Who receives an email for each new website request (needs the SMTP settings of step 3). | Your email address(es), separated by commas. |
| GOOGLE_PLACES_API_KEY | Google ratings and reviews on a lead. | Google Cloud console → APIs & Services → enable **Places API (New)** → Credentials → API key (restrict it to that API). |
| PLACES_DAILY_LIMIT | Maximum Google lookups per day (default 100). | A number you choose, to cap the cost. |
| GOOGLE_REVIEW_URL | Link sent to clients to ask for a Google review. | Google Business Profile → **Ask for reviews** → copy the link. |
| WEEKLY_REPORT_CRON | When the Monday coaching email goes out (Montréal time; needs the SMTP settings of step 3). | Do not set it for Monday 7:30 (`0 30 7 * * MON`); otherwise a Spring cron with seconds, e.g. `0 0 8 * * MON`. |
| OVERPASS_URL | A preferred OpenStreetMap server for map discovery, tried first. | Leave empty to use the built-in list of public servers (the server falls back to the next one on error and remembers the one that works for an hour). |

Passwords: each user can change their own password in **Réglages → Mon compte**; an administrator can reset someone else's in **Réglages → Équipe**. Sessions already open stay valid until they expire (the login lasts 24 hours); to force everyone out, replace JWT_REFRESH_SECRET and JWT_SECRET as in step 8.
