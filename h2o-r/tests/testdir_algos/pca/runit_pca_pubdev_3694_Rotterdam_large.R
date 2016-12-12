library(h2o)
h2o.init(nthreads = -1, max_mem_size = "8G")


file <- "/Users/wendycwong/h2o-3/bigdata/laptop/jira/rotterdam.csv.zip"
df <- h2o.importFile(file)
dim(df)  # 286 22284

y <- "relapse"
x <- setdiff(names(df), y)
df[,y] <- as.factor(df[,y])  #Convert to factor (for binary classification)


splits <- h2o.splitFrame(df, seed = 1)
train <- splits[[1]]
test <- splits[[2]]
print(dim(train))
print(dim(test))


# Does not work:
# Train a default PCA
h2o_pca <- h2o.prcomp(train, k = 8, x = x)

#Error: java.lang.IllegalArgumentException: Found validation errors: ERRR on field: _train: Gram matrices (one per thread) won't fit in the driver node's memory (59.19 GB > 6.93 GB) - try reducing the number of columns and/or the number of categorical factors.
# Also kills the H2O cluster!


# Try again with Power method instead, but this errors out and kills the cluster!
# Train a PCA model using 20 principal components.
h2o_pca20 <- h2o.prcomp(train,
x = x, k = 20,
transform = "STANDARDIZE",
pca_method = "Power",
use_all_factor_levels = TRUE,
seed = 1)
