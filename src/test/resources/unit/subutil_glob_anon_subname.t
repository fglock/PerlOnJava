use strict;
use warnings;
use Test::More;
use Sub::Util qw(subname set_subname);

my $anon = sub { 1 };
{
    no strict 'refs';
    *Foo::t = $anon;
}

is(subname($anon), 'main::__ANON__', 'glob-installed anonymous coderef keeps original subname');
ok(Foo->can('t'), 'glob-installed anonymous coderef is callable from target stash');

my $renamed = set_subname('Foo::renamed', sub { 1 });
{
    no strict 'refs';
    *Foo::renamed = $renamed;
}

is(subname($renamed), 'Foo::renamed', 'explicit set_subname still wins');

{
    package SourceForAutoclean;

    sub install_t {
        no strict 'refs';
        *TargetForAutoclean::t = sub { 1 };
    }
}

{
    package TargetForAutoclean;
    use namespace::autoclean;
    BEGIN { SourceForAutoclean::install_t() }
}

ok(
    !TargetForAutoclean->can('t'),
    'namespace::autoclean removes imported anonymous coderef',
);

done_testing();
