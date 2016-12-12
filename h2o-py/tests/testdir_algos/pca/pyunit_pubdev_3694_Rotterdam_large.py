from __future__ import print_function
from builtins import str
from builtins import range
import sys
sys.path.insert(1,"../../../")
import h2o
from tests import pyunit_utils
from h2o.transforms.decomposition import H2OPCA



def pca_3694_rotterdam():


  print("Importing Rotterdam.csv data...")
  rotterdamH2O = h2o.upload_file(pyunit_utils.locate("bigdata/laptop/jira/rotterdam.csv.zip"))
  rotterdamH2O.describe()

  y = set(["relapse"])
  x = list(set(rotterdamH2O.names)-y)

  # GLRM model is supposed to work according to Erin
  pca_glrm = H2OPCA(k=20, transform="STANDARDIZE", pca_method="GLRM", use_all_factor_levels=True, seed=123)
  pca_glrm.train(x=x, training_frame=rotterdamH2O)

  pca_h2o = H2OPCA(k=8)
  pca_h2o.train(x=x, training_frame=rotterdamH2O)

if __name__ == "__main__":
  pyunit_utils.standalone_test(pca_3694_rotterdam)
else:
  pca_3694_rotterdam()
