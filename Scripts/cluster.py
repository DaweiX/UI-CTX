import json
import mplcursors
import numpy as np
from matplotlib import pyplot as plt
import scipy.cluster.hierarchy as sch
from sklearn.manifold import TSNE
from collections import defaultdict
from sklearn.preprocessing import LabelEncoder
from os.path import join, abspath, dirname
from sklearn.neighbors import NearestNeighbors
from sklearn.metrics import precision_score, recall_score, f1_score
import sys
import argparse


def get_value(_line, _name) -> float:
    return float(_line.split(f"{_name}: ")[1].split(",")[0])


def calculate_knn_metrics(data, _labels, _k=10):
    # Use LabelEncoder to convert labels and predictions to separate integer encodings
    label_encoder = LabelEncoder()

    # Fit and transform labels and predictions
    encoded_labels = label_encoder.fit_transform(_labels)

    knn = NearestNeighbors(n_neighbors=_k)
    knn.fit(data)

    neighbors = knn.kneighbors(data, return_distance=False)
    
    _preds = np.zeros_like(_labels)
    for i, indices in enumerate(neighbors):
        neighbor_labels = _labels[indices]
        unique, counts = np.unique(neighbor_labels, return_counts=True)
        _preds[i] = unique[np.argmax(counts)]

    unique_labels = np.unique(_labels)

    _precision = precision_score(_labels, _preds, labels=unique_labels, average=None)
    _recall = recall_score(_labels, _preds, labels=unique_labels, average=None)
    _f1 = f1_score(_labels, _preds, labels=unique_labels, average=None)

    label_names = label_encoder.inverse_transform(np.unique(encoded_labels))
    _precision_per_class = dict(zip(label_names, _precision))
    _recall_per_class = dict(zip(label_names, _recall))
    _f1_per_class = dict(zip(label_names, _f1))

    return _precision_per_class, _recall_per_class, _f1_per_class


def hierarchy_cluster(data: np.array, t=200, method='ward', metric='euclidean'):
    distance_matrix = sch.distance.pdist(data, metric=metric)
    linkage_matrix = sch.linkage(distance_matrix, method=method)
    cluster_labels = sch.fcluster(linkage_matrix, t=t, criterion='maxclust')
    return cluster_labels


def run_tsne(data, tsne_perplexity: int = 50,
             tsne_lr: float = 200,
             tsne_iter: int = 1000):
    tsne = TSNE(metric='euclidean',
                perplexity=tsne_perplexity,
                learning_rate=tsne_lr,
                max_iter=tsne_iter,
                n_components=2)
    tsne.fit_transform(data)
    return tsne.embedding_


def draw_cluster(data, names, cluster_indices, _labels):
    fig = plt.figure()
    ax = fig.add_subplot()
    if cluster_indices is not None:
        index2color = {idx: i for i, idx in enumerate(set(cluster_indices))}
        colors = [index2color[i] for i in cluster_indices]
        sc = ax.scatter(data[:, 0], data[:, 1], c=colors, cmap='tab20')
        ax.legend(handles=sc.legend_elements()[0], labels=[i for i in set(cluster_indices)])
    else:
        sc = ax.scatter(data[:, 0], data[:, 1])
        ax.legend()

    cursor = mplcursors.cursor(sc, hover=True)
    @cursor.connect("add")
    def on_add(sel):
        index = sel.index
        sel.annotation.set(text=f"Name: {names[index]}\n"
                                f"Label: {_labels[index]}\n"
                                f"Cluster: {cluster_indices[index]}")
        sel.annotation.get_bbox_patch().set(fc="white", alpha=0.8)

    def on_click(event):
        if event.inaxes == ax:
            contains, ind = sc.contains(event)
            if contains:
                idx = ind['ind'][0]  # Get the index of the selected point
                print(names[idx], _labels[idx])

    # noinspection PyTypeChecker
    fig.canvas.mpl_connect('button_press_event', on_click)
    plt.show()

    
def calculate_hca_metrics(_labels, preds):
    # Use LabelEncoder to convert labels and preds to separate integer encodings
    label_encoder = LabelEncoder()
    pred_encoder = LabelEncoder()
    
    # Fit and transform labels and predictions
    encoded_labels = label_encoder.fit_transform(_labels)
    encoded_preds = pred_encoder.fit_transform(preds)

    # Get unique labels and predictions
    unique_labels = np.unique(encoded_labels)
    unique_preds = np.unique(encoded_preds)

    confusion_matrix = np.zeros((len(unique_labels), len(unique_labels)))
    matched_clusters_per_label = defaultdict(list)

    # Calculate the dominant label for each cluster
    for prediction in unique_preds:
        indices_in_cluster = np.where(encoded_preds == prediction)[0]
        true_labels_in_cluster = encoded_labels[indices_in_cluster]
        if len(true_labels_in_cluster) > 0:  # Ensure there are labels in the cluster
            # Find the dominant label (most frequent)
            dominant_label = np.argmax(np.bincount(true_labels_in_cluster))
            matched_clusters_per_label[dominant_label].append(prediction)

    _precision_per_class = {}
    _recall_per_class = {}
    _f1_per_class = {}

    for label in unique_labels:
        matched_clusters = matched_clusters_per_label.get(label, [])
        if not matched_clusters:
            _precision_per_class[label_encoder.inverse_transform([label])[0]] = 0
            _recall_per_class[label_encoder.inverse_transform([label])[0]] = 0
            _f1_per_class[label_encoder.inverse_transform([label])[0]] = 0
            continue

        # True Positive: Samples correctly predicted as the current label
        true_positive = np.sum([(encoded_labels == label) &
                                (encoded_preds == cluster)
                                for cluster in matched_clusters])
        
        # False Positive: Samples incorrectly predicted as the current label
        false_positive = np.sum([np.sum((encoded_preds == cluster) &
                                        (encoded_labels != label))
                                 for cluster in matched_clusters])
        
        # False Negative: Samples of the current label incorrectly predicted as other labels
        false_negative = np.sum((encoded_labels == label) &
                                ~np.isin(encoded_preds, matched_clusters))

        true_positive_count = np.sum(true_positive)
        false_positive_count = np.sum(false_positive)
        false_negative_count = np.sum(false_negative)

        p = true_positive_count / (true_positive_count + false_positive_count) \
            if true_positive_count + false_positive_count > 0 else 0
        r = true_positive_count / (true_positive_count + false_negative_count) \
            if true_positive_count + false_negative_count > 0 else 0
        f1 = (2 * p * r) / (p + r) if p + r > 0 else 0

        # Map the integer label back to the original label name
        label_name = label_encoder.inverse_transform([label])[0]
        _precision_per_class[label_name] = p
        _recall_per_class[label_name] = r
        _f1_per_class[label_name] = f1

        # Fill the confusion matrix
        confusion_matrix[label, label] += true_positive_count

        for cluster in matched_clusters:
            # Count the misclassified predictions for the current label
            misclassified_count = np.sum((encoded_preds == cluster) & (encoded_labels != label))
            # Get the true labels for the misclassified samples
            true_labels_for_misclassified = encoded_labels[(encoded_preds == cluster)
                                                           & (encoded_labels != label)]

            for true_label in np.unique(true_labels_for_misclassified):
                confusion_matrix[label, true_label] += misclassified_count

    return _precision_per_class, _recall_per_class, _f1_per_class, confusion_matrix


def run_cluster(_encode_type: str, _label_dict: dict, algo: str):
    data = np.load(join(work_path, f"{_encode_type}.npz"))
    _embeddings = data["e_list"]
    _behavior_names = data["n_list"]
    _mask = np.array([i in _label_dict for i in _behavior_names])
    _behavior_names = np.array(_behavior_names)[_mask]
    _embeddings = np.array(_embeddings)[_mask]

    if "uhg" in _encode_type:
        first_col = _embeddings[:, 0]
        min_v = first_col.min()
        max_v = first_col.max()
        normalized_first_col = (first_col - min_v) / (max_v - min_v)
        _embeddings[:, 0] = normalized_first_col

    _labels = [_label_dict[name] for name in _behavior_names]
    _labels = np.array(_labels)

    _precision_per_class, _recall_per_class, _f1_per_class = {}, {}, {}
    if algo == "hca":
        preds = hierarchy_cluster(_embeddings)
        preds = np.array(preds)
        _precision_per_class, _recall_per_class, _f1_per_class, _ = \
            calculate_hca_metrics(_labels, preds)
    elif algo == "knn":
        _precision_per_class, _recall_per_class, _f1_per_class = \
            calculate_knn_metrics(_embeddings, _labels)
    return _precision_per_class, _recall_per_class, _f1_per_class


def parse_arg_cluster(_args: list):
    parser = argparse.ArgumentParser(description="run cluster analysis and get results")
    parser.add_argument("type", help="representation type", type=str,
                        choices=["pms", "seq", "uhg", "all"])
    parser.add_argument("--viz_tsne", "-vt",
                        help="visualization by tsne", action="store_true")
    parser.add_argument("--cluster_algo", "-ca",
                        help="algorithm for clustering", type=str,
                        choices=["hca", "knn"], default="hca")
    return parser.parse_args(_args)


if __name__ == '__main__':
    args = parse_arg_cluster(sys.argv[1:])
    encode_type = args.type
    work_path = join(dirname(dirname(abspath(__file__))), "Data", "Benchmark")

    if args.viz_tsne:
        label_dict = {}
        bench_file = join(work_path, "bench.json")
        with open(bench_file, "r") as f:
            obj = json.load(f)
        for key in obj:
            for d in obj[key]:
                label_dict[d] = key

        data_npz = np.load(join(work_path, f"{encode_type}.npz"))
        embeddings = data_npz["e_list"]
        behavior_names = data_npz["n_list"]
        mask = np.array([i in label_dict for i in behavior_names])
        behavior_names = np.array(behavior_names)[mask]
        embeddings = np.array(embeddings)[mask]

        # normalize the structural info in uhg
        if encode_type == "uhg":
            first_column = embeddings[:, 0]
            min_val = first_column.min()
            max_val = first_column.max()
            normalized_first_column = (first_column - min_val) / (max_val - min_val)
            embeddings[:, 0] = normalized_first_column

        data_labels = [label_dict[d] for d in behavior_names]
        embedding_2d = run_tsne(embeddings)
        draw_cluster(embedding_2d, behavior_names, None,data_labels)
    else:
        label_dict = {}
        bench_file = join(work_path, "bench.json")
        with open(bench_file, "r") as f:
            obj = json.load(f)
        for key in obj:
            for d in obj[key]:
                label_dict[d] = key

        print("sample num:", len(label_dict.keys()))

        bar_len = 39
        if args.type != "all":
            print("-" * 18 + args.type + "-" * 18)
            precision_per_class, recall_per_class, f1_per_class = \
                run_cluster(args.type, label_dict, args.cluster_algo)
            print('-' * bar_len)
            print('category\tp\tr\tf1')
            print('-' * bar_len)
            for key in f1_per_class:
                print(f"{key:8}\t"
                      f"{precision_per_class[key]:.2f}\t"
                      f"{recall_per_class[key]:.2f}\t"
                      f"{f1_per_class[key]:.3f}")
            print('-' * bar_len)
        else:
            results = {key: {} for key in ["p", "r", "f1"]}
            char2metric = {"p": "precision", "r": "recall", "f1": "f1-score"}
            types = ["pms", "seq", "uhg"]
            f1_uhg = []
            ratios = []
            ratios_pms, ratios_seq = [], []

            for _type in types:
                precision_per_class, recall_per_class, f1_per_class = \
                    run_cluster(_type, label_dict, args.cluster_algo)
                results["f1"][_type] = f1_per_class
                results["p"][_type] = precision_per_class
                results["r"][_type] = recall_per_class

            for k in results:
                print('-' * bar_len)
                print(f'{char2metric[k]:8}\tpms\tseq\tuhg')
                print('-' * bar_len)
                labels = results[k]["pms"]
                for cate in labels:
                    print(f"{cate:8}\t", end="")
                    row = [f"{results[k][_type][cate]:.3f}" for _type in types]
                    print("\t".join(row))

            f1_scores = results["f1"]

            zero_count_pms = 0
            zero_count_seq = 0
            for key in f1_scores["uhg"]:
                f1_uhg.append(f1_scores["uhg"][key])
                _max = max(f1_scores["pms"][key], f1_scores["seq"][key])
                ratios.append((f1_scores["uhg"][key] - _max) / _max)
                if f1_scores["pms"][key] > 0:
                    ratios_pms.append((f1_scores["uhg"][key] - f1_scores["pms"][key])
                                      / f1_scores["pms"][key])
                else:
                    zero_count_pms += 1
                if f1_scores["seq"][key] > 0:
                    ratios_seq.append((f1_scores["uhg"][key] - f1_scores["seq"][key])
                                      / f1_scores["seq"][key])
                else:
                    zero_count_seq += 1

            avg_ratio_pms = sum(ratios_pms) / len(ratios_pms)* 100.
            avg_ratio_seq = sum(ratios_seq) / len(ratios_seq)* 100.
            print('-' * bar_len)
            print(f"avg f1: {np.mean(f1_uhg):.2f}")
            print(f"A: avg f1 up% (uhg vs. seq): {avg_ratio_seq:.2f}% "
                  f"{zero_count_seq if zero_count_seq > 0 else ''}")
            print(f"B: avg f1 up% (uhg vs. pms): {avg_ratio_pms:.2f}% "
                  f"{zero_count_pms if zero_count_pms > 0 else ''}")
            print(f"avg f1 up% (avg of A and B): {((avg_ratio_seq + avg_ratio_pms) / 2):.2f}%")
