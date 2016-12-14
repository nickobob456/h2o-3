from __future__ import print_function
from builtins import str
from builtins import range
import sys
sys.path.insert(1,"../../../")
from tests import pyunit_utils
import h2o


def h2ols():
    """
    Testing the h2o.init() command here.

    :return: none if test passes
    """
    iris = h2o.import_file(path=pyunit_utils.locate("smalldata/iris/iris.csv"))
    lsObject = h2o.ls()
    # check return type as DataFrame
    assert type(lsObject).__name__ == 'DataFrame', "h2o.ls() return type should be DataFrame."
    # check that our frame info was included in the lsObject
    assert lsObject.values[0][0] == str(iris.frame_id), \
        "Frame info iris.hex should have been found but not from h2o.ls()"

if __name__ == "__main__":
    pyunit_utils.standalone_test(h2ols)
else:
    h2ols()
