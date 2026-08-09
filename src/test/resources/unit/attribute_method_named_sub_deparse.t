use strict;
use warnings;
use Test::More tests => 6;
use Attribute::Handlers;
use B::Deparse;

package LocalMethodAttribute;

my $deparse;
my @deparsed_sources;
BEGIN { $deparse = B::Deparse->new('-l') }

sub import {
    my $package = caller;
    no strict 'refs';
    *{"${package}::self"} = \undef;
}

sub UNIVERSAL::Method : ATTR(RAWDATA) {
    my ($package, $symbol, $code, undef, $arguments) = @_;
    my $source = $deparse->coderef2text($code);
    push @deparsed_sources, $source;
    $source =~ s/\{/{\nmy \$self = shift;\n/;
    my $name = *{$symbol}{NAME};
    no warnings 'redefine';
    eval "package $package; sub $name $source";
    die $@ if $@;
}

eval {
    package NamedSubDeparse;
    use strict;
    use warnings;
    BEGIN { LocalMethodAttribute->import }

    sub new : Method {
        42;
    }

    sub get : Method {
        43;
    }
};

package main;

is($@, '', 'attribute handler does not recursively reapply a named-sub attribute');
is(NamedSubDeparse->new, 42, 'first deparsed replacement preserves its body');
is(NamedSubDeparse->get, 43, 'second deparsed replacement preserves its body');

my $new_source = $deparse->coderef2text(\&NamedSubDeparse::new);
my $get_source = $deparse->coderef2text(\&NamedSubDeparse::get);
unlike($new_source, qr/:\s*Method/, 'replacement source omits the original attribute');
unlike($get_source, qr/:\s*Method/, 'second replacement source omits the original attribute');
like($deparsed_sources[1], qr/43/, 'compile-time handler receives the second exact source span');
