# coding=utf-8
# Licensed Materials - Property of IBM
# Copyright IBM Corp. 2017
from typing import Any
from typing import Callable
from typing import Dict
from typing import List
import unittest
from streamsx.topology.topology import Stream
from streamsx.topology.topology import Topology

class Tester(object):
    def __init__(self, topology: Topology) -> None: ...

    @staticmethod
    def setup_standalone(test: unittest.TestCase) -> None: ...
    @staticmethod
    def setup_distributed(test: unittest.TestCase) -> None: ...
    @staticmethod
    def setup_streaming_analytics(test: unittest.TestCase, service_name: str=None, force_remote_build: bool=False) -> None: ...

    def tuple_count(self, stream: Stream, count: int, exact: bool=True) -> Stream: ...
    def contents(self, stream: Stream, expected: List[Any], ordered: bool=False) -> Stream: ...
    def tuple_check(self, stream: Stream, checker: Callable[[Any],Any]) -> Any: ...

    def local_check(self, callable: Callable[[],None]) -> None: ...

    def test(self, ctxtype: Any, config: Dict[str,Any]=None, assert_on_fail: bool=True, username: str=None, password: str=None) -> bool: ...