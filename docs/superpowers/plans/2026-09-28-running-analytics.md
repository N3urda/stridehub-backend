# Running analytics implementation plan

**Goal:** Complete the approved running assistant and add a professional, Chinese running dashboard reachable from activity records and run details.

**Architecture:** Keep imported Activity/ActivityStream/Split/Lap/BestEffort data authoritative. Add read-only, authenticated overview and detail APIs with a standalone ECharts admin page. No synthetic user statistics, inferred running dynamics, or automatic plan changes. Work in the user-selected checkout.

**Stack:** Symfony/PHP 8.5, SQLite DBAL, Twig, local ECharts, vanilla JS, PHPUnit and real HTTP/browser checks.

- [x] Finish training review: reproduce historical matching failure after 500 newer runs, query the relevant time window, verify partial feedback, reminders and API concurrency with existing suites.
- [x] Implement `RunningOverview`: all matching runs contribute to totals and week/month charts, weighted aggregate pace, dated bounds, sports filter, paginated records, period best efforts. Test empty data, >500 rows, null HR, filtering and zero-distance pace.
- [x] Implement `RunAnalysis`: raw timestamps and values, time-weighted HR distribution, sparse intervals, pauses and gaps, bounded chart samples, pace/HR scatter, equal-distance halves and split consistency. Test unequal sampling, missing values, pauses and invalid timestamps. Document formulas and coverage; do not name summary changes fitness or readiness.
- [x] Implement `RunningDetails`: fetch real streams/splits/laps with compressed-data decoder; running-only IDs, optional thresholds from existing athlete settings. Expose `/api/v1/running/overview` and `/activities/{id}` plus admin-session mirrors; test 401, 404, 422 and real records.
- [x] Add `/admin/running`, overview/detail charts, date/sport filters, pagination, CSV export, hover/zoom, raw tables, data-source coverage. Link from existing activities and run detail with ordinary links, preserving native map/detail navigation.
- [x] Validate numeric algorithms, API/auth, Twig/container, PHPStan and scoped style. Run real browser desktop/mobile, empty/full/missing stream cases, filter and navigation without fixture data leaking into personal records.
- [x] Document API, Zepp data limits, metric methodology and runtime setup. Review changes and commit the completed feature with verified results.
