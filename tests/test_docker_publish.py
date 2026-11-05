import importlib.util
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("docker_publish", Path(__file__).resolve().parents[1] / "scripts/docker_publish.py")
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


def tag(name, digit="a"):
    return {"name": name, "tag_status": "active", "digest": "sha256:" + digit * 64}


class DockerPublishTest(unittest.TestCase):
    @patch.object(publish.subprocess, "check_output", return_value="a" * 40 + "\n")
    def test_manual_release_resolves_the_tag_commit_not_main(self, resolve):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            with patch.dict(os.environ, {"GITHUB_REF": "refs/heads/main", "PATRIA_RELEASE_TAG": "v0.5.0", "GITHUB_OUTPUT": str(output)}), patch("sys.argv", ["docker_publish.py", "plan"]):
                runpy.run_path(str(Path(publish.__file__)), run_name="__main__")
            self.assertIn("tag=0.5.0\nstable=true\ncommit=" + "a" * 40, output.read_text())
            self.assertIn("refs/tags/v0.5.0^{commit}", resolve.call_args.args[0])

    def test_channels(self):
        self.assertEqual(("unstable", False), publish.channel("refs/heads/main"))
        self.assertEqual(("1.10.0", True), publish.channel("refs/tags/v1.10.0"))
        for ref in ["refs/heads/feature", "refs/tags/v1.0.0-rc.1", "refs/tags/v1.0", "refs/tags/v01.0.0", "refs/tags/v1.0.0+build"]:
            with self.assertRaises(ValueError):
                publish.channel(ref)

    def test_old_tag_republication_selects_highest_published_version(self):
        selected = publish.highest_stable([tag("1.9.0"), tag("unstable"), tag("1.10.0"), tag("2.0.0-rc.1")], "1.9.0")
        self.assertEqual("1.10.0", selected["name"])

    def test_missing_version_or_invalid_digest_fails_closed(self):
        for tags in [[tag("1.0.0")], [dict(tag("1.1.0"), digest="invalid")]]:
            with self.assertRaises(ValueError):
                publish.highest_stable(tags, "1.1.0")

    @patch.object(publish.subprocess, "run")
    @patch.object(publish, "hub_tags", return_value=[tag("1.0.0")])
    def test_first_latest_uses_exact_digest(self, hub, run):
        publish.promote("1.0.0")
        self.assertEqual("supersouris7/patria@sha256:" + "a" * 64, run.call_args.args[0][-1])

    @patch.object(publish.subprocess, "run")
    @patch.object(publish.subprocess, "check_output", return_value='{"config":{"Labels":{"org.opencontainers.image.version":"2.0.0"}}}')
    @patch.object(publish, "hub_tags", return_value=[tag("1.0.0"), tag("latest", "b")])
    def test_latest_cannot_regress_even_if_tag_listing_is_stale(self, hub, inspect, run):
        publish.promote("1.0.0")
        run.assert_not_called()

    @patch.object(publish.subprocess, "run")
    @patch.object(publish.subprocess, "check_output", return_value='{}')
    @patch.object(publish, "hub_tags", return_value=[tag("1.0.0"), tag("latest", "b")])
    def test_unknown_latest_is_not_overwritten(self, hub, inspect, run):
        with self.assertRaises(ValueError):
            publish.promote("1.0.0")
        run.assert_not_called()

    def test_prune_keeps_latest_unstable_and_newest_version_only(self):
        tags = [tag("latest"), tag("unstable"), tag("0.5.0"), tag("0.4.2"), tag("0.10.0"), tag("a" * 40), tag("62c1912"), tag("ma-version-perso")]
        self.assertEqual(sorted(["0.4.2", "0.5.0", "a" * 40, "62c1912"]), publish.to_delete(tags))

    def test_prune_without_version_keeps_channels(self):
        self.assertEqual(["b" * 40], publish.to_delete([tag("unstable"), tag("b" * 40)]))
        self.assertEqual([], publish.to_delete([tag("latest"), tag("unstable")]))

    @patch.object(publish, "hub_token")
    @patch.object(publish, "hub_tags", return_value=[tag("unstable"), tag("c" * 40)])
    def test_dry_run_deletes_nothing(self, hub, login):
        publish.prune(True)
        login.assert_not_called()


if __name__ == "__main__":
    unittest.main()
