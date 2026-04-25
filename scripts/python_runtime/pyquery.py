from bs4 import BeautifulSoup, FeatureNotFound


def _create_soup(value):
    markup = str(value)
    for parser in ("lxml", "html.parser"):
        try:
            return BeautifulSoup(markup, parser)
        except FeatureNotFound:
            continue
    return BeautifulSoup(markup, "html.parser")


class PyQuery:
    def __init__(self, value=None, elements=None):
        if elements is not None:
            self._elements = [item for item in elements if item is not None]
            return

        if isinstance(value, PyQuery):
            self._elements = list(value._elements)
            return

        if isinstance(value, bytes):
            value = value.decode("utf-8", errors="ignore")

        if value is None:
            value = ""

        if hasattr(value, "select"):
            self._elements = [value]
            return

        soup = _create_soup(value)
        self._elements = [soup]

    def __call__(self, selector):
        if not selector:
            return PyQuery(elements=self._elements)
        matched = []
        for element in self._elements:
            if hasattr(element, "select"):
                matched.extend(element.select(selector))
        return PyQuery(elements=matched)

    def __bool__(self):
        return bool(self._elements)

    def __len__(self):
        return len(self._elements)

    def items(self):
        for element in self._elements:
            yield PyQuery(elements=[element])

    def eq(self, index):
        if not self._elements:
            return PyQuery(elements=[])
        try:
            return PyQuery(elements=[self._elements[index]])
        except IndexError:
            return PyQuery(elements=[])

    def text(self):
        texts = []
        for element in self._elements:
            if hasattr(element, "get_text"):
                text = element.get_text(" ", strip=True)
                if text:
                    texts.append(text)
        return " ".join(texts).strip()

    def attr(self, name):
        if not self._elements:
            return None
        element = self._elements[0]
        if not hasattr(element, "attrs"):
            return None
        value = element.attrs.get(name)
        if isinstance(value, list):
            return " ".join(str(item) for item in value if item is not None)
        return value
