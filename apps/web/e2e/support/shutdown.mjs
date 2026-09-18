// Explicit teardown of these isolated servers avoids Windows shell-tree kill
// hangs and never scans for or stops any native operator service.
export default async function shutdown() {
  await Promise.all(
    [19091, 19093].map(async (port) => {
      try {
        const result = await fetch(`http://127.0.0.1:${port}/__e2e_shutdown`, {
          method: "POST",
          headers: { "x-test-key": "isolated-browser-acceptance-only" },
          signal: AbortSignal.timeout(5000),
        });
        if (!result.ok)
          throw new Error(
            `Isolated test server ${port} rejected teardown: ${result.status}`,
          );
      } catch (error) {
        // A server which already exited requires no cleanup. Surface other errors.
        if (error?.cause?.code !== "ECONNREFUSED") throw error;
      }
    }),
  );
}
