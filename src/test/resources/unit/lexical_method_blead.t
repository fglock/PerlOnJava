use strict;
use warnings;
use feature 'class';
no warnings 'experimental::class';
use Test::More tests => 6;

class UnitLexicalMethod {
    field $value = 'private';

    my method priv ($suffix = '') {
        return $value . $suffix;
    }

    method direct {
        return priv($self, '-direct');
    }

    method via_arrow {
        return $self->&priv('-arrow');
    }
}

is(UnitLexicalMethod->new->direct, 'private-direct',
    'class methods can call a lexical method by name');
is(UnitLexicalMethod->new->via_arrow, 'private-arrow',
    'class methods can invoke lexical methods with ->&');
ok(!UnitLexicalMethod->can('priv'),
    'lexical methods are absent from the package stash');

class UnitLexicalMethodWithSignature {
    field $value = 123;

    my method format_value ($suffix) {
        return "$value:$suffix";
    }

    method run {
        return $self->&format_value('ok');
    }
}

is(UnitLexicalMethodWithSignature->new->run, '123:ok',
    'lexical method signatures retain the implicit self argument');
ok(!UnitLexicalMethodWithSignature->can('format_value'),
    'signed lexical methods remain private');

class UnitArrowDirectCall {
    method value { return 'package-result'; }
    method invoke { return $self->&value; }
}

class UnitArrowDirectCallChild :isa(UnitArrowDirectCall) {
    method value { return 'child-result'; }
}

is(UnitArrowDirectCallChild->new->invoke, 'package-result',
    '->& calls the current package method without inheritance dispatch');
