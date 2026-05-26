use strict;
use warnings;
use Test::More tests => 2;
use mro 'c3';

{
    package NextCacheBase;
    sub insert { "base:$_[1]" }

    package NextCacheExtension;
    use mro 'c3';
    our @ISA = ('NextCacheBase');
    sub insert { 'extension:' . next::method(@_) }

    package NextCacheMaker;
    use mro 'c3';
    our @ISA = ('NextCacheExtension');

    package NextCacheStorage;
    sub insert { NextCacheMaker->insert($_[1]) }
}

is(NextCacheStorage->insert('first'), 'extension:base:first',
    'bare next::method resolves before method-cache warmup');
is(NextCacheStorage->insert('second'), 'extension:base:second',
    'bare next::method resolves through cached method dispatch');
