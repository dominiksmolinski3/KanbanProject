import unittest

from dhi_pins_refresh import REPOSITORY, refresh

OLD = "a" * 64
FILE = """monitoring_binaries:
  - name: prometheus
    version: 3.16.0
    deb: {repo}pool/trixie/main/p/pr/prometheus-3.16_3.16.0-3/prometheus-3.16_3.16.0-3_amd64.deb
    sha256: {sha}
    executables: [prometheus, promtool]
  - name: node_exporter
    version: 1.12.1
    deb: {repo}pool/trixie/main/n/no/node-exporter_1.12.1-9/node-exporter_1.12.1-9_amd64.deb
    sha256: {other}
    executables: [node_exporter]
"""


def stanza(package, version, sha, directory, arch="amd64"):
    return (f"Package: {package}\nVersion: {version}\nArchitecture: {arch}\n"
            f"Filename: pool/trixie/main/{directory}/{package}_{version}/{package}_{version}_{arch}.deb\nSHA256: {sha}\n")


def text(sha=OLD, other="b" * 64):
    return FILE.format(repo=REPOSITORY, sha=sha, other=other)


class Refresh(unittest.TestCase):
    def test_leaves_a_pin_that_matches_the_index_alone(self):
        index = "\n".join([stanza("prometheus-3.16", "3.16.0-3", OLD, "p/pr"),
                           stanza("node-exporter", "1.12.1-9", "b" * 64, "n/no")])
        updated, changes = refresh(text(), index)
        self.assertEqual(text(), updated)
        self.assertEqual([], changes)

    def test_repins_a_revision_dhi_replaced_in_place(self):
        index = "\n".join([stanza("prometheus-3.16", "3.16.0-3", "c" * 64, "p/pr"),
                           stanza("node-exporter", "1.12.1-9", "b" * 64, "n/no")])
        updated, changes = refresh(text(), index)
        self.assertEqual(text(sha="c" * 64), updated)
        self.assertEqual(1, len(changes))
        self.assertIn("replaced revision 3 in place", changes[0])

    def test_moves_to_the_newest_revision_of_the_pinned_upstream_version(self):
        index = "\n".join([stanza("node-exporter", "1.12.1-9", "b" * 64, "n/no"),
                           stanza("node-exporter", "1.12.1-11", "d" * 64, "n/no"),
                           stanza("node-exporter", "1.12.1-10", "e" * 64, "n/no"),
                           stanza("prometheus-3.16", "3.16.0-3", OLD, "p/pr")])
        updated, changes = refresh(text(), index)
        self.assertIn("node-exporter_1.12.1-11/node-exporter_1.12.1-11_amd64.deb", updated)
        self.assertIn("sha256: " + "d" * 64, updated)
        self.assertEqual(["- `node_exporter` 1.12.1: revision 9 to 11"], changes)

    def test_never_crosses_to_another_upstream_version_or_architecture(self):
        index = "\n".join([stanza("node-exporter", "1.12.1-9", "b" * 64, "n/no"),
                           stanza("node-exporter", "1.13.0-1", "f" * 64, "n/no"),
                           stanza("node-exporter", "1.12.1-12", "f" * 64, "n/no", arch="arm64"),
                           stanza("prometheus-3.16", "3.16.0-3", OLD, "p/pr")])
        updated, changes = refresh(text(), index)
        self.assertEqual(text(), updated)
        self.assertEqual([], changes)

    def test_fails_when_the_index_no_longer_lists_a_pin(self):
        with self.assertRaisesRegex(ValueError, "lists no node-exporter 1.12.1-N"):
            refresh(text(), stanza("prometheus-3.16", "3.16.0-3", OLD, "p/pr"))

    def test_fails_on_a_file_it_cannot_read_rather_than_passing(self):
        with self.assertRaisesRegex(ValueError, "no monitoring_binaries entry"):
            refresh("monitoring_binaries: []\n", "")

    def test_keeps_windows_line_endings(self):
        index = "\n".join([stanza("prometheus-3.16", "3.16.0-3", "c" * 64, "p/pr"),
                           stanza("node-exporter", "1.12.1-9", "b" * 64, "n/no")])
        crlf = text().replace("\n", "\r\n")
        self.assertEqual(text(sha="c" * 64).replace("\n", "\r\n"), refresh(crlf, index)[0])


if __name__ == "__main__":
    unittest.main()
