# Synthetic discovery currency fixture

`payment-discovery-no-currency.xlsx` copies the repository's public
`apps/web/public/payment-discovery-template.xlsx`. Only the data values in its
optional `CURRENCY` column (H, after the header) are blanked. References, amounts,
rows, styles and other workbook parts are unchanged. It contains synthetic demo
payments, not bank exports.

The browser test uploads this file through the real Java discovery parser to
create cases whose original currency is absent. After adding matching PAYMENT
evidence with `CODCURR=INR`, it checks that the original currency remains null and
that the UI displays the evidence currency with its source version.
