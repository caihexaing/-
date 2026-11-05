import unittest

from compile_mobile_filter import compile_mobile


class MobileFilterChecks(unittest.TestCase):
    def test_compiles_only_domain_rules_and_keeps_host_exceptions(self):
        output, counts = compile_mobile("""
! header
||ads.example.com^
||ads.example.com^$third-party
||bad/path^
@@||ads.example.com^$app=com.example.app
@@||cdn.example.net/script.js$domain=example.net
@@||site.example.com^$generichide
@@*$app=com.example.app
""", "base.example.net\n")
        self.assertIn("ads.example.com\n", output)
        self.assertIn("~ads.example.com\n", output)
        self.assertIn("~cdn.example.net\n", output)
        self.assertIn("base.example.net\n", output)
        self.assertNotIn("site.example.com", output)
        self.assertEqual(counts["blocked"], 2)
        self.assertEqual(counts["exactAllowed"], 2)

    def test_rejects_invalid_domain_safely(self):
        output, counts = compile_mobile("||127.0.0.1^\n||valid.example^")
        self.assertIn("valid.example\n", output)
        self.assertEqual(counts["blocked"], 1)


if __name__ == "__main__":
    unittest.main()
