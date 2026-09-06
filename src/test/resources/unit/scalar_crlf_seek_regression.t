#!/usr/bin/env perl
use Test::More;

my $crlf = "\r\n";
my $contents = join '', map { "$_$crlf" } 'a' .. 'zzz';
open my $fh, '<:crlf', \$contents or die "open scalar handle: $!";

{
    local $/ = 'xxx';
    local $_ = <$fh>;
    my $position = tell $fh;
    is($position, index($contents, "xxx$crlf") + 3,
       'localized scalar readline consumes only through its separator');
    ok(seek($fh, $position, 0), 'seek to the layered handle position');

    $/ = "\n";
    $scalar_crlf_seek_value = <$fh> . <$fh>;
    is($scalar_crlf_seek_value, "\nxxy\n", 'seek preserves the CRLF layer boundary');
}

done_testing;
