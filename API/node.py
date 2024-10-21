class Node:
    def __init__(self, row) -> None:
        # common attributes
        self._name = row["Name"]
        self._hash = row["Hash"]
        self._widget = row["UI"]

    @property
    def name(self):
        """name of the node"""
        return self._name
    
    @property
    def hash(self):
        """name of the node"""
        return self._hash
    
    def is_widget(self):
        return self._widget > 0
    

class MethodNode(Node):
    def __init__(self, row) -> None:
        super().__init__(row)

        # attributes for methods
        self._java_api = row["Java"]
        self._android_api = row["Android"]
        self._class = row["Class"]
        self._package = row["Package"]

    def is_api(self):
        """is native api"""
        return self._java_api + self._android_api > 0
    
    @property
    def klass(self):
        """class of the method"""
        return self._class
    
    @property
    def package(self):
        """package of the method"""
        return self._package
    

class WidgetNode(Node):
    def __init__(self, row) -> None:
        super().__init__(row)
        self._xml = row["XML"]
        self._wid = row["UId"]

    @property
    def xml(self):
        """layout xml where the widget comes from"""
        return self._xml
    
    @property
    def wid(self):
        """id of the widget"""
        return self._wid