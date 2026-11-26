import unittest

from rate_limit_rerun import rate_limited_line


class RateLimitedLine(unittest.TestCase):
    def test_reads_the_refusals_seen_in_this_repository(self):
        for refusal in (
            "ERROR: failed to build: failed to solve: invalid response status 429",
            "[ERROR] Could not transfer artifact x from/to central (https://repo.maven.apache.org/maven2): HTTP Status: 429",
            "HTTP 429: Too Many Requests (https://api.github.com/graphql)",
            "toomanyrequests: You have reached your unauthenticated pull rate limit.",
            "GraphQL: API rate limit exceeded for installation",
            "You have exceeded a secondary rate limit",
            "npm error 429 Too Many Requests - GET https://registry.npmjs.org/x",
        ):
            with self.subTest(refusal):
                self.assertIsNotNone(rate_limited_line("before\n" + refusal + "\nafter"))

    def test_reads_through_terminal_colour(self):
        self.assertIsNotNone(rate_limited_line("\x1b[31mHTTP Status: 429\x1b[0m"))

    def test_ignores_a_429_that_is_only_digits_in_something_else(self):
        for noise in (
            "sha256:d3244e8ce3f165ee3c93d9c4a4b1d0fe48b3a89e2287b717fdcbbde92d405065 4291",
            "Run 45429 finished in 429ms",
            "port 4290 is in use",
            "HTTP 4290",
        ):
            with self.subTest(noise):
                self.assertIsNone(rate_limited_line(noise))

    def test_ignores_failures_that_are_not_rate_limits(self):
        for failure in (
            "sha256sum: WARNING: 1 computed checksum did NOT match",
            "##[error]no kind label; add one of bug, enhancement, documentation, techdebt",
            "HTTP 502: 502 Bad Gateway (https://api.github.com/graphql)",
            "Tests run: 12, Failures: 1, Errors: 0",
        ):
            with self.subTest(failure):
                self.assertIsNone(rate_limited_line(failure))

    def test_ignores_the_event_payload_a_pull_request_body_can_fill(self):
        self.assertIsNone(rate_limited_line('> {"github_event_payload":{"body":"toomanyrequests"}}'))

    def test_names_the_line_it_matched(self):
        self.assertEqual(
            "invalid response status 429",
            rate_limited_line("noise\n  invalid response status 429  \nmore"),
        )


if __name__ == "__main__":
    unittest.main()
