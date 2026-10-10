# Verification: the rules that prevent self-deception
> **Normative.** They are not recommendations. They are the answer to concrete errors that have already been made. Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** The verification rules remain in force; the commands cite the previous backend. The command in force is `./gradlew verify`. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

**1. Read the test XML files, never "BUILD SUCCESSFUL".**
Gradle reports success with 0 tests executed.

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for mod in ['stock-domain','backend']:
    t=f=0
    for p in glob.glob(mod+'/build/test-results/**/TEST-*.xml', recursive=True):
        r=ET.parse(p).getroot(); t+=int(r.get('tests',0)); f+=int(r.get('failures',0))
    print(f'{mod}: {t} tests, {f} failures')
"
```

**2. Prove that a guard fails before trusting it.**
A guard is verified by deliberately removing the condition it watches: if the tests stay green, the guard
watches nothing. **A test that has never failed does not prove that it watches anything.**

**3. The tests compile the code; the server runs an artifact.**
They are two different claims. After changing code:

```bash
./gradlew :backend:installDist && restart
```

This error already produced a `NoClassDefFoundError` in production with the suite in green.

**4. Test a URL with `curl` before writing the interface.**
`tsc` and `eslint` **do not validate URLs at runtime**. The `/v1/v1/...` bug passed both
([web-fsd.md](../arquitectura/web-fsd.md) §2.3).

**5. An alarming result is also verified before believing it.**
A malformed query can make it seem that the ledger lost rows. Before investigating
a data loss, it is checked that the query measures what it is believed to measure.

**6. Parallel agents need disjoint files.**
Two processes that edit the same file at the same time break the build (`FileNotFoundException`
on a compiled class). **Those that compile with Gradle are serialized**, and **two agents
cannot touch the same file**, ever.

The test levels and how they are run are in
[estrategia-pruebas.md](../historico/ESTRATEGIA-PRUEBAS-ANTERIOR.md).

## A `./gradlew test` in green does not mean that all the tests were run

Checked on 2026-10-10, and it is the most expensive trap that has appeared: **`./gradlew test`
does not run `:stock-domain:jvmTest`**. The domain project is multiplatform
(Kotlin Multiplatform) and its test task is called `jvmTest`, so the aggregate `test` —which does
walk the JVM modules— left it out.

The symptom is exactly the one this document warns about elsewhere: the report
said `BUILD SUCCESSFUL`, and the count of domain tests was read from the XML files of a
**previous** run. For two rounds "407 domain tests, 0 failures" was reported while there was a domain test in red that nobody was
running (`AdjustmentTest` expected the legacy location code, `04BCA`, after the visible label had become `04-B-C`).

What was done:

- The verification command is **`./gradlew verify`**, a task that depends on all the test
  tasks of all the modules (`test` and `jvmTest`) and on the architecture guards. If a module changes the name of its task, `verify` has to be updated.
- **Delete the XML files before measuring** (`rm -rf */build/test-results`). A count without
  deleting is a count of the last time something ran.

The general rule added to those above: **a test report is only worth something if it is known which task produced it and when**. A `BUILD SUCCESSFUL` of a task that does not include the module that was touched says nothing about that module.

## A migration has to be re-applicable

Checked on 2026-10-10, and it cost a red deployment: when writing migration `V14` I applied it by hand against the database to check the SQL, and when Flyway ran it the column was already created and **the migration service failed with `exit 1`**. The symptom was worse than it seems: the API did not start and the `curl` checks returned `000` (connection refused) while the response file of the previous answer was still in `/tmp`, so the first glance said "403, all good".

Two rules that come from that:

- **The DDL of a migration goes with `IF NOT EXISTS`** (`ALTER TABLE … ADD COLUMN IF NOT
  EXISTS`, `CREATE INDEX IF NOT EXISTS`). Flyway guarantees applying it once, but a
  human may have applied it before, and being able to re-apply it costs nothing.
- **When a check fails, look at the HTTP code and not at the body of the previous file.** A `000` is not a `403`: the first one means that nobody answered.

## A `docker compose build` can come out entirely from cache and leave the old jar

Checked on 2026-10-10, and it is the most silent trap of all in this document:
`docker compose build api` answered `Image stockflow-api Built` without executing **a single compile step** —all the layers `CACHED`—, so the image kept the previous jar.
The container started healthy, `/v1/health` answered `UP`, and the new route answered
`401 UNAUTHENTICATED` as if it did not exist. I almost accepted it.

What gave it away: the same request **passed** in the integration tests, which start the application
from the freshly compiled classes. If a test and a `curl` check say different things, the suspect is the artifact that runs, not the test.

How to check it without guessing: read the jar **inside the image**.

```bash
docker run --rm --entrypoint sh stockflow-api -c \
  'unzip -p /app/app.jar BOOT-INF/classes/dev/axzy/stock/app/config/SecurityConfig.class | strings | grep -c "print-jobs"'
```

Two details needed for that to work, which also cost me a while:

- A Spring Boot jar **does not carry the project's classes loose**: they go in nested jars
  (`BOOT-INF/lib/adapters-web-0.1.0.jar`). A `unzip -l | grep PrintJobController` on the
  outer jar returns `0` even though the code is inside, and it looks as if it is missing when it is there.
- That is why one has to look at the class of the `app` module (which does go loose in
  `BOOT-INF/classes/`) or extract the nested jar first.

And what is done when it happens: `docker compose up -d --force-recreate api`, and check the route again
against the artifact that runs. The rule above still applies and now has its own name: **the artifact that runs is not the compiled code**, and an image that
was built "successfully" without compiling anything is not a new image.

## `write` over a test file that already existed erases what was there

Checked on 2026-10-10, and it is a mistake of mine that deserves to be written down: when writing the
XLSX reading tests, **I wrote `CatalogUploadTest.kt` without checking that it already existed**, and the
eight tests of the CSV/TSV reader that were inside disappeared. What gave it away was `git status`, which marked the file as `M` (modified) instead of new,
and the count of the module: the web layer went from 13 tests to 11 **after** adding six.

Two rules that come from that:

- **Before writing a test file, `git status` or a `glob`.** If it exists, it is read and
  extended; overwriting it is erasing work that was already verified.
- **The test count per module is a guard.** A module that gains tests and goes down in number has a problem, even if the suite is green. The number is checked before and after.

What was done: recover the file from the repository and **merge** it with the new tests,
after replacing the only one that had become obsolete (the one that checked that XLSX was rejected, because it is now read). The count went up again and the eight originals are still there,
with the four new ones that did not repeat anything.

## A test that fails one hour a day: "today" is not the machine's date

Checked on 2026-10-10, and it cost a bit of confusion: two report tests started failing with the movement summary **empty**. It was not the change that had just been made: it was the time. The machine was in `PDT` (October 9, 23:02) and the API's business zone is
`America/Mexico_City` (UTC-6), where it was already **October 10**. The test asked for
`from=today&to=today` with `LocalDate.now()` —the **machine's** date— while the API dates the
movements in the business zone, so it searched the wrong day.

It is a one-hour-a-day failure, the worst kind: invisible almost always and disconcerting when it appears. The rule that comes from it:

- **A test that asks for "today" has to compute it the way the API computes it**, with the same zone and from the same configuration. In this project that is
  `businessToday()` (`LocalDate.now(properties.businessZone)`), which lives in `ApiTest` so that
  there are not two ways of saying it.
- It applies to any date filter: reports, ledger and audit. If a test builds a date with a bare `LocalDate.now()`, it is on the machine's time and will fail some day.

## A test that reads a bootstrap password breaks at the first change

Checked on 2026-10-10: the sign-in test against the real API failed with "Wrong user or password",
although the password was the one in `.env`. It was not a failure of the API: the administrator had already
changed their password, which is what the API requires at first sign-in. `ADMIN_BOOTSTRAP_PASSWORD`
is only used when creating the account, and the test took it as the current password.

The rule that comes from it: **a test needs its own credential**, not the one that only serves to start up. It is now `E2E_ADMIN_PASSWORD`, with `ADMIN_BOOTSTRAP_PASSWORD` only as a fallback. If a test fails with a credential that "should work", first check whether the account changed its own.
