#!/usr/bin/env perl
# Generate the document index in dev/design/README.md.
#
#   perl dev/tools/gen_design_index.pl           rewrite the index
#   perl dev/tools/gen_design_index.pl --check   exit 1 if the index is stale
#
# The index is the text between the BEGIN/END markers. Each row shows the
# document's first heading and the first "Status:" line found near the top.
use strict;
use warnings;
use utf8;
use File::Basename qw(dirname);
use File::Spec;

my $check  = grep { $_ eq '--check' } @ARGV;
my $root   = File::Spec->catdir(dirname(File::Spec->rel2abs($0)), '..', '..');
my $dir    = File::Spec->catdir($root, 'dev', 'design');
my $readme = File::Spec->catfile($dir, 'README.md');
my ($begin, $end) = ('<!-- BEGIN GENERATED INDEX -->', '<!-- END GENERATED INDEX -->');

opendir my $dh, $dir or die "Cannot read $dir: $!";
my @docs = sort { lc $a cmp lc $b } grep { /\.md\z/ && $_ ne 'README.md' } readdir $dh;
closedir $dh;

my @rows = ('| Document | Title | Status |', '|---|---|---|');
for my $doc (@docs) {
    open my $fh, '<:encoding(UTF-8)', File::Spec->catfile($dir, $doc) or die "$doc: $!";
    my ($title, $status) = ('', '');
    while (my $line = <$fh>) {
        last if $. > 30;
        if (!$title && $line =~ /^#\s+(.+?)\s*$/) { $title = $1 }
        if (!$status && $line =~ /^[>\s#*_]*(?:Plan |Current )?Status[*_]*\s*[:—-]\s*[*_]*\s*(.+?)\s*$/i) { $status = $1 }
    }
    close $fh;
    for ($title, $status) {
        s/\[([^\]]*)\]\([^)]*\)/$1/g;    # drop link targets
        s/[*_`]//g;
        s/\|/\\|/g;
    }
    $status =~ s{^(.{1,60})\b.*$}{$1…}s if length $status > 60;
    $status =~ s{[\s.,;:(—-]+(…?)$}{$1};
    push @rows, sprintf '| [%s](%s) | %s | %s |', $doc, $doc, $title || '—', $status || '—';
}
my $index = join("\n", $begin, '', @rows, '', $end);

open my $in, '<:encoding(UTF-8)', $readme or die "$readme: $!";
my $text = do { local $/; <$in> };
close $in;
my $new = $text;
$new =~ s/\Q$begin\E.*\Q$end\E/$index/s or die "Markers not found in $readme\n";

if ($check) {
    exit 0 if $new eq $text;
    print STDERR "dev/design/README.md index is stale; run: perl dev/tools/gen_design_index.pl\n";
    exit 1;
}
if ($new ne $text) {
    open my $out, '>:encoding(UTF-8)', $readme or die "$readme: $!";
    print $out $new;
    close $out;
}
