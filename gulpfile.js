const fs = require('fs')
const utils = require('util')
const cp = require('child_process')
const exec = utils.promisify(cp.exec)
const path = require('path')
const gulp = require('gulp')
const cleanCSS = require('gulp-clean-css')
const del = require('del')

const outputPath = path.join(__dirname, 'static')
const resourcesPath = path.join(__dirname, 'resources')
const publicStaticPath = path.join(__dirname, 'public/static')
const sourcePath = path.join(__dirname, 'src/main/frontend')
const resourceFilePath = path.join(resourcesPath, '**')
const outputFilePath = path.join(outputPath, '**')

const css = {
  watchCSS () {
    return cp.spawn(`yarn css:watch`, {
      shell: true,
      stdio: 'inherit'
    })
  },

  buildCSS (...params) {
    return gulp.series(
      () => exec(`yarn css:build`, {}),
      css._optimizeCSSForRelease
    )(...params)
  },

  _optimizeCSSForRelease () {
    return gulp.src(path.join(outputPath, 'css', 'style.css'))
      .pipe(cleanCSS())
      .pipe(gulp.dest(path.join(outputPath, 'css')))
  }
}

const common = {
  clean () {
    return del(['./static/**/*', '!./static/yarn.lock', '!./static/node_modules'])
  },

  syncResourceFile () {
    return gulp.src(resourceFilePath).pipe(gulp.dest(outputPath))
  },

  syncAssetFiles () {
    return gulp.src([
        "./node_modules/@excalidraw/excalidraw/dist/excalidraw-assets/**",
        "!**/*/i18n-*.js"
      ])
      .pipe(gulp.dest(path.join(outputPath, 'js', 'excalidraw-assets')))
  },

  keepSyncResourceFile () {
    return gulp.watch(resourceFilePath, { ignoreInitial: true }, common.syncResourceFile)
  },

  syncAllStatic () {
    return gulp.src([
      outputFilePath,
      '!' + path.join(outputPath, 'node_modules/**')
    ]).pipe(gulp.dest(publicStaticPath))
  },

  syncJS_CSSinRt () {
    return gulp.src([
      path.join(outputPath, 'js/**'),
      path.join(outputPath, 'css/**')
    ], { base: outputPath }).pipe(gulp.dest(publicStaticPath))
  },

  keepSyncStaticInRt () {
    return gulp.watch([
      path.join(outputPath, 'js/**'),
      path.join(outputPath, 'css/**')
    ], { ignoreInitial: true }, common.syncJS_CSSinRt)
  }
}

exports.electron = () => {
  if (!fs.existsSync(path.join(outputPath, 'node_modules'))) {
    cp.execSync('yarn', {
      cwd: outputPath,
      stdio: 'inherit'
    })
  }

  cp.execSync('yarn electron:dev', {
    cwd: outputPath,
    stdio: 'inherit'
  })
}

exports.electronMaker = async () => {
  cp.execSync('yarn cljs:release-electron', {
    stdio: 'inherit'
  })

  const pkgPath = path.join(outputPath, 'package.json')
  const pkg = require(pkgPath)
  const version = fs.readFileSync(path.join(__dirname, 'src/main/frontend/version.cljs'))
    .toString().match(/[0-9.]{3,}/)[0]

  if (!version) {
    throw new Error('release version error in src/**/*/version.cljs')
  }

  pkg.version = version
  fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2))

  if (!fs.existsSync(path.join(outputPath, 'node_modules'))) {
    cp.execSync('yarn', {
      cwd: outputPath,
      stdio: 'inherit'
    })
  }

  cp.execSync('yarn electron:make', {
    cwd: outputPath,
    stdio: 'inherit'
  })
}

exports.clean = common.clean
exports.watch = gulp.series(common.syncResourceFile, common.syncAssetFiles, common.syncAllStatic,
  gulp.parallel(common.keepSyncResourceFile, css.watchCSS, common.keepSyncStaticInRt))
exports.build = gulp.series(common.clean, common.syncResourceFile, common.syncAssetFiles, css.buildCSS)
