from functools import partial
import json
import numpy as np
from tqdm import tqdm
from os.path import join, exists, abspath, dirname
import pandas as pd
from scipy.sparse.linalg import eigsh
from scipy.sparse import identity, diags
import xml.dom.minidom
import multiprocessing


# noinspection SpellCheckingInspection
def jimple_to_smali(jimple_signature):
    # ref: https://github.com/JesusFreke/smali/wiki/TypesMethodsAndFields
    jimple_signature = jimple_signature.strip()
    return_type, rest = jimple_signature.split(' ', 1)
    method_name, params = rest.split('(', 1)
    params = params.rstrip(')')
    
    smali_return_type = java_to_smali_type(return_type)
    smali_params = ''.join(java_to_smali_type(param.strip()) for param in params.split(',') if param)
    
    smali_signature = f"{method_name}({smali_params}){smali_return_type}"
    return smali_signature

def java_to_smali_type(java_type):
    type_mapping = {
        'void': 'V',
        'boolean': 'Z',
        'byte': 'B',
        'char': 'C',
        'short': 'S',
        'int': 'I',
        'long': 'J',
        'float': 'F',
        'double': 'D',
    }
    
    if java_type in type_mapping:
        return type_mapping[java_type]
    
    if java_type.endswith('[]'):
        return '[' + java_to_smali_type(java_type[:-2])

    if '<' in java_type:
        java_type = java_type[:java_type.index('<')]
    
    java_type = java_type.replace('.', '/')
    return f'L{java_type};'


def parse_map():
    results = {}
    permissions = []
    _work_path_root = dirname(dirname(abspath(__file__)))
    fap = join(_work_path_root, "Data", "api_permission.csv")
    with open(fap, mode="r") as f:
        for line in f:
            api, pms = line.strip().split(',')
            api = api.replace(' ', '')
            results[api] = pms
            permissions.append(pms)
    
    print("permission count:", len(set(permissions)))
    return results, list(set(permissions))
        

def get_api(out_folder):
    soot_log = join(out_folder, "code.log")
    api = ""
    if exists(soot_log):
        with open(soot_log, encoding="utf-8", mode="r") as f:
            for line in f:
                if "API ver:" in line:
                    api = line.split("ver: ")[1].split(' ')[0]
    else:
        manifest = join(out_folder, "manifest.xml")
        if exists(manifest):
            with open(manifest, mode="r", encoding="utf-8") as f:
                try:
                    doc = xml.dom.minidom.parse(f)
                    m = doc.getElementsByTagName("manifest")[0]
                    s = m.getElementsByTagName("uses-sdk")
                    if len(s) > 0:
                        s = s[0]
                        tgt_sdk, min_sdk = "targetSdkVersion", "minSdkVersion"
                        if tgt_sdk in s.attributes.keys():
                            api = s.getAttribute(tgt_sdk)
                        elif min_sdk in s.attributes.keys():
                            api = s.getAttribute(min_sdk)
                except:
                    print("bad manifest xml")

    if api == "":
        print("cannot find api level")
        api = "14"
    # print(f"API level: {api}")

    api_level = f"android-{api}"
    return api_level


def graph2vec(d: str, root_path: str):
    _, apk, index = d.split('-')
    index = int(index.split('.')[0]) - 1 
    f_behavior = join(root_path, apk, "uhg.json")
    with open(f_behavior, mode='r', encoding='utf-8') as fb:
        try:
            json_obj = json.load(fb)
        except:
            return None
        behavior = json_obj[index]
        edges = behavior['edges']
        edges = [e.split(' ')[:2] for e in edges if e.strip().endswith('0')]
        node_seq = np.array(edges).flatten()
        node_seq_int = [int(i) for i in node_seq]
        nodes = list(set(node_seq_int))
        index_dict = {value: index for index, value in enumerate(nodes)}
        new_edges = [index_dict[n] for n in node_seq_int]
        if len(new_edges) == 0:
            return None

        # load info
        node_csv = join(root_path, apk, "node.csv")
        if not exists(node_csv):
            print(f"no node.csv found in {join(root_path, apk)}")
            return None
        
        names = pd.read_csv(node_csv, encoding="utf-8",
                            usecols=["Name", "Package"], low_memory=False)

        names = names.loc[:, "Name"].to_numpy()
        names = names[nodes].tolist()
        return names, d


def graph2pms(d: str, root_path: str, 
              permission_map: dict, permissions: list):
    _, apk, index = d.split('-')
    permission_count = len(permissions)
    permission2id = {p:i for i,p in enumerate(permissions)}
    index = int(index.split('.')[0]) - 1 
    f_behavior = join(root_path, apk, "uhg.json")
    with open(f_behavior, mode='r', encoding='utf-8') as fb:
        try:
            json_obj = json.load(fb)
        except:
            return None
        behavior = json_obj[index]
        edges = behavior['edges']
        edges = [e.split(' ')[:2] for e in edges if e.strip().endswith('0')]
        node_seq = np.array(edges).flatten()
        node_seq_int = [int(i) for i in node_seq]
        nodes = list(set(node_seq_int))
        index_dict = {value: index for index, value in enumerate(nodes)}
        new_edges = [index_dict[n] for n in node_seq_int]
        if len(new_edges) == 0:
            return None

        # load info
        node_csv = join(root_path, apk, "node.csv")
        if not exists(node_csv):
            print(f"no node.csv found in {join(root_path, apk)}")
            return None

        names = pd.read_csv(node_csv, encoding="utf-8",
                        usecols=["Name"], low_memory=False)

        names = names.loc[:, "Name"].tolist()
        e = [0] * permission_count
        for ni in nodes:
            name = names[ni]
            # dummyMainMethod
            if ':' not in name:
                continue
            klass, method = name[1:-1].split(":")
            klass = klass.replace(".", "/")
            method = method.strip()
            smethod = jimple_to_smali(method)
            smali_name = f"L{klass};->{smethod.replace(',', ';')}"
            if smali_name in permission_map:
                e[permission2id[permission_map[smali_name]]] = 1
        return e, d

def graph2uhg(d: str, root_path: str, op_path: str):
    OP_TYPE_NUM = 264
    _, apk, index = d.split('-')
    with open(join(root_path, apk, "op_code.json"), mode="r") as f:
        op_json = json.load(f)
    api = get_api(join(root_path, apk))
    with open(join(op_path, "sdk", f"{api}.json"), mode="r") as ff:
            op_json_api = json.load(ff)
    index = int(index.split('.')[0]) - 2 
    f_behavior = join(root_path, apk, "uhg.json")
    with open(f_behavior, mode='r', encoding='utf-8') as fb:
        try:
            json_obj = json.load(fb)
        except:
            return None
        behavior = json_obj[index]
        edges = behavior['edges']

        # no need to keep relation here
        edges = [e.split(' ')[:2] for e in edges if e.strip().endswith('0')]
        node_seq = np.array(edges).flatten()

        # load info
        node_csv = join(root_path, apk, "node.csv")
        if not exists(node_csv):
            print(f"no node.csv found in {join(root_path, apk)}")
            return None

        names = pd.read_csv(node_csv, encoding="utf-8",
                        usecols=["Name"], low_memory=False)

        names = names.loc[:, "Name"].tolist()
        node_seq_int = [int(i) for i in node_seq]

        # edge, need to map old id to new one
        nodes = list(set(node_seq_int))
        nodes.sort()
        index_dict = {value: index for index, value in enumerate(nodes)}
        new_edges = [index_dict[n] for n in node_seq_int]
        if len(new_edges) == 0:
            return None
        new_edges = np.reshape(new_edges, (len(edges), 2))
        new_edges = np.transpose(new_edges)
        edge_index = torch.tensor(new_edges.tolist(), dtype=torch.long)

        # save node embedding
        fail = 0.
        x = np.zeros(shape=(len(nodes), OP_TYPE_NUM))
        for i, ni in enumerate(nodes):
            name = names[ni][1:-1]
            klass, method = name.split(":")
            klass = klass.replace(".", "/")
            klass = f"L{klass};"
            method = method.strip()
            method_s = jimple_to_smali(method)
            op_seq = None
            if klass in op_json:
                oo = op_json[klass]
                if method_s in oo:
                    op_seq = oo[method_s]
            if op_seq is None:
                try:
                    op_seq = op_json_api[klass][method_s]
                except KeyError as e:
                    if klass.startswith("Ljava/"):
                        op_seq = [104, 15]
                    else:
                        op_seq = [0]    # NOP
                        fail += 1
            for j in op_seq:
                x[i][j] = 1

        x = x.tolist()
        try:
            e = normalized_laplacian_spectrum(x, edge_index, len(nodes))
            return e, d
        except Exception as e:
            print(str(e))
            return None


def normalized_laplacian_spectrum(x, edge_index, num_nodes, k=2):
    adj = to_scipy_sparse_matrix(edge_index, num_nodes=num_nodes).tocoo()
    row_sum = np.array(adj.sum(1)).flatten()
    row_sum[row_sum == 0] = 1e-10
    D_inv_sqrt = diags(1.0 / np.sqrt(row_sum))
    L = identity(num_nodes) - D_inv_sqrt @ adj @ D_inv_sqrt
    if k >= num_nodes:
        eigvals = np.linalg.eigvalsh(L.toarray())
    else:
        eigvals, _ = eigsh(L, k=k, which='SM')
    eigvals = np.sort(eigvals)
    eigvals = eigvals[:k] if len(eigvals) > k else np.pad(eigvals, (0, k - len(eigvals)), 'constant')
    feature_mean = np.mean(x, axis=0)
    feature_std = np.std(x, axis=0)
    return np.concatenate([[np.mean(eigvals, axis=0)], feature_mean, feature_std])


def encode(op_path: str, encode_type: str="uhg"):
    data_path = join(dirname(dirname(abspath(__file__))), "Data")
    if encode_type not in ["uhg", "pms", "seq"]:
        print(f"unknown encode type: {encode_type}")
        exit(-1)
    data = []
    root_path = join(data_path, "ui-ctx-data")
    with open(join(data_path, "Benchmark", "bench.json"), mode="r") as f:
        obj = json.load(f)
        for key in obj:
            data.extend(obj[key])

    thread = 4

    manager = multiprocessing.Manager()
    multiprocessing.log_to_stderr()
    shared_dict = manager.dict()
    for c in ["e", "n"]:
        shared_dict[c] = manager.list()

    permission_map, permissions = None, None
    if encode_type == "pms":
        permission_map, permissions = parse_map()

    if encode_type == "uhg":
        from torch_geometric.utils import to_scipy_sparse_matrix
        import torch

    with multiprocessing.Pool(processes=thread) as pool:
        with tqdm(total=len(data), desc="encoding graphs") as pbar:
            # ----------------permission----------------
            if encode_type == "pms":
                func = partial(graph2pms, root_path=root_path, 
                               permission_map=permission_map,
                               permissions=permissions)
            # -------------ui handler graph-------------
            elif encode_type == "uhg":
                func = partial(graph2uhg, root_path=root_path, 
                               op_path=op_path)
            # ---------------call sequence--------------
            elif encode_type == "seq":
                func = partial(graph2vec, root_path=root_path)

            for result in pool.imap_unordered(func, data):
                update_result(result, shared_dict)
                pbar.update(1)

    nd = convert_to_regular_dict(shared_dict)
    if encode_type == "seq":
        from gensim.models import Word2Vec
        print("running word2vec", end="...")
        vectors = nd["e"]
        model = Word2Vec(sentences=vectors, vector_size=64,
                         window=5, min_count=1, workers=4)

        model_dict = {word: model.wv[word].tolist() for word in model.wv.index_to_key}
        e_list_new = []
        for v in vectors:
            a = []
            for vv in v:
                a.append(model_dict[vv])
            e_list_new.append(np.mean(a, axis=0).tolist())
        nd["e"] = e_list_new
        print("done!")

    np.savez(join(data_path, "Benchmark", "{encode_type}.npz"), 
             e_list=nd["e"], n_list=nd["n"])


def convert_to_regular_dict(d):
    regular_dict = {}
    for k, v in d.items():
        if isinstance(v, multiprocessing.managers.ListProxy):
            regular_dict[k] = list(v)
        elif isinstance(v, dict):
            regular_dict[k] = convert_to_regular_dict(v)
        else:
            regular_dict[k] = v
    return regular_dict

def update_result(result, shared_dict):
    if result is None:
        return

    e, n = result
    shared_dict["e"].append(e)
    shared_dict["n"].append(n)

if __name__ == "__main__":
    work_path_root = dirname(dirname(abspath(__file__)))
    encode("", "pms")
