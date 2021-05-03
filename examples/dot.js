// engines:
// - dot: reasonable but elongated
// - fdp: good fit but tons of crossing lines
// - osage: boxes tightly packet, no layout on lines
//
// - neato: overlapping clusters/groups
// - patchwork: one packed box, no visible lines
// - circo: sparse rectilinear layout, no clusters/groups
// - twopi: circo-like with diagonals, no clusters/groups
const DEFAULT_ENGINE = 'dot'

function downloadSVG(container) {
  const svg = document.getElementById(container).innerHTML;
  const blob = new Blob([svg.toString()]);
  const element = document.createElement("a");
  element.download = `${container}.svg`;
  element.href = window.URL.createObjectURL(blob);
  element.click();
  element.remove();
}

const params = new URLSearchParams(location.search)

const dotFile = params.get('data') || 'data.dot'
const engine = params.get('engine') || DEFAULT_ENGINE
console.log(`Fetching '${dotFile}'`)
fetch(dotFile)
  .then(resp => resp.text())
  .then(text => {
    console.log(`Loaded dot data:\n`, text)
    console.log(`Rendering using engine '${engine}'`)
    d3.select('#graph')
      .graphviz()
      .engine(engine)
      .renderDot(text)
  })
