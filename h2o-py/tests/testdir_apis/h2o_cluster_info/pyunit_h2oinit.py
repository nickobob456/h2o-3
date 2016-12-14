from __future__ import print_function
from builtins import str
from builtins import range
import sys
sys.path.insert(1,"../../../")
from tests import pyunit_utils
import h2o


def h2oinit():
    """
    Testing the h2o.init() command here.

    :return: none if test passes or error message otherwise
    """
    try:
        h2o.init()
    except Exception as e:  # some errors are okay like version mismatch
        assert "Version mismatch." in e.args[0], "Wrong exception messages found.  h2o.init() command not working"


if __name__ == "__main__":
    pyunit_utils.standalone_test(h2oinit)
else:
    h2oinit()
