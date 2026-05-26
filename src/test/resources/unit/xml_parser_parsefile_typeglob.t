#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use XML::Parser;

{
    package Local::Parser::ParsefileProbe;
    our @ISA = ('XML::Parser');

    sub _is_typeglob_handle {
        my ($source) = @_;
        return 0 if ref($source);
        no strict 'refs';
        return eval { *{$source}{IO} } ? 1 : 0;
    }

    sub parse {
        my ($self, $source) = @_;
        push @{ $self->{seen_typeglob_handles} }, _is_typeglob_handle($source);
        return wantarray ? ('parsed', 'list') : 'parsed';
    }
}

my ($fh, $filename) = tempfile();
print {$fh} "<root/>\n";
close $fh or die "close $filename: $!";

my $parser = bless {}, 'Local::Parser::ParsefileProbe';

is($parser->parsefile($filename), 'parsed', 'scalar parsefile result is forwarded');
my @result = $parser->parsefile($filename);
is_deeply(\@result, ['parsed', 'list'], 'list parsefile result is forwarded');

is_deeply(
    $parser->{seen_typeglob_handles},
    [1, 1],
    'parsefile passes a typeglob handle to subclass parse wrappers'
);
is($parser->{Base}, undef, 'parsefile restores Base after parsing');

unlink $filename or die "unlink $filename: $!";

done_testing();
