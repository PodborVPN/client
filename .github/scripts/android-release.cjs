const { Manifest } = require('release-please/build/src/manifest');
const { GitHub } = require('release-please/build/src/github');
const { ManifestPlugin } = require('release-please/build/src/plugin');

class AndroidRelease extends ManifestPlugin {
  processCommits(commits) {
    return commits.filter(({ scope }) => scope === 'android' || scope === 'shared');
  }
}

async function prepare() {
  const [owner, repo] = process.env.GITHUB_REPOSITORY.split('/');
  const github = await GitHub.create({ owner, repo, token: process.env.GH_TOKEN });
  const manifest = await Manifest.fromManifest(github, 'main');
  manifest.plugins.push(new AndroidRelease(github, 'main', manifest.repositoryConfig));
  await manifest.createPullRequests();
}

if (require.main === module) {
  prepare().catch(error => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

module.exports = { AndroidRelease };
