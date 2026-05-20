use strict;
use warnings;
use Test::More;
use Cwd ();
use File::Glob ();
use File::Spec;
use File::Temp qw(tempdir);

SKIP: {
    skip 'Unix File::Spec canonpath cases', 4 if $^O eq 'MSWin32';

    is(File::Spec->canonpath('~idontthinkso\\*'), '~idontthinkso\\*',
        'File::Spec::Unix canonpath treats backslash as literal');
    is(File::Spec->canonpath('/../../'), '/', 'canonpath clamps absolute updirs at root');
    is(File::Spec->canonpath('/../..'), '/', 'canonpath clamps absolute terminal updirs at root');
    is(File::Spec->canonpath('///../../..//./././a//b/.././c/././'), '/a/b/../c',
        'canonpath matches File::Spec::Unix absolute cleanup');
}

is_deeply([File::Glob::bsd_glob('~blah blah')], ['~blah blah'],
    'File::Glob::bsd_glob keeps whitespace in one literal pattern');
is_deeply([File::Glob::bsd_glob('~idontthinkso\\*')], ['~idontthinkso*'],
    'File::Glob::bsd_glob dequotes escaped glob metacharacters');
is_deeply([File::Glob::bsd_glob('~i\\{dont,think}so',
    File::Glob::GLOB_NOCHECK() | File::Glob::GLOB_BRACE() | File::Glob::GLOB_QUOTE())],
    ['~i{dont,think}so'], 'File::Glob::bsd_glob dequotes no-check literals');
my ($glob_home) = File::Glob::bsd_glob('~');
ok(defined($glob_home) && $glob_home ne '~', 'File::Glob::bsd_glob expands bare tilde');

{
    package PathTinyLocalizedCodeSlot;
    sub target { 'old' }
    package main;
    my $orig = \&PathTinyLocalizedCodeSlot::target;
    {
        no warnings 'redefine';
        local *PathTinyLocalizedCodeSlot::target = sub { 'new' };
        is(PathTinyLocalizedCodeSlot::target(), 'new',
            'direct named calls see localized CODE glob slots');
        is($orig->(), 'old', 'saved coderef keeps original CODE value');
    }
    is(PathTinyLocalizedCodeSlot::target(), 'old',
        'localized CODE glob slot restores after scope exit');
}

{
    my $dir = File::Temp->newdir('helloXXXXX', TMPDIR => 1);
    isa_ok($dir, 'File::Temp::Dir');
    like("$dir", qr/hello/, 'File::Temp->newdir keeps leading template with options');
}

{
    my $file = File::Temp->new(TEMPLATE => 'helloXXXXX', TMPDIR => 1);
    like("$file", qr/hello/, 'File::Temp->new keeps TMPDIR template');
}

{
    my $dir = tempdir(CLEANUP => 1);
    my $old = Cwd::getcwd();
    chdir $dir or die "chdir $dir: $!";
    my $file = File::Temp->new(DIR => '.', TMPDIR => 1);
    ok(-e "$file", 'File::Temp->new creates relative DIR files under Perl cwd');
    close $file;
    chdir $old or die "chdir $old: $!";
}

{
    package PathTinyHintProbe;
    sub hints {
        my $h = (caller(0))[10] || {};
        return ($h->{'open<'}, $h->{'open>'});
    }
    package main;
    {
        use open IO => ':utf8';
        is_deeply([PathTinyHintProbe::hints()], [':utf8', ':utf8'],
            'open pragma stores caller hinthash entries');
    }
}

{
    sub _poj_path_tiny_lines { ("Line1\r\n", "Line2\n") }
    my @got = map { s/[\r\n]+\z//; $_ } _poj_path_tiny_lines();
    is_deeply(\@got, ['Line1', 'Line2'], 'sub-returned constants are mutable map temporaries');
}

{
    my $dir = tempdir(CLEANUP => 1);
    my $old = Cwd::getcwd();
    chdir $dir or die "chdir $dir: $!";
    mkdir 'nested' or die "mkdir nested: $!";
    open my $fh, '>', 'nested/file.txt' or die "open nested/file.txt: $!";
    close $fh;
    ok(utime(undef, undef, 'nested/file.txt'), 'utime resolves relative to Perl cwd');
    chdir $old or die "chdir $old: $!";
}

SKIP: {
    my $dir = tempdir(CLEANUP => 1);
    my $target = "$dir/target";
    open my $fh, '>', $target or die "open $target: $!";
    close $fh;

    skip 'symlink not available', 5 unless symlink '../target', "$dir/subprobe";
    unlink "$dir/subprobe";

    mkdir "$dir/sub" or die "mkdir $dir/sub: $!";
    my $expected_target = $^O eq 'MSWin32' ? '..\\target' : '../target';
    ok(symlink('../target', "$dir/sub/link"), 'relative symlink created');
    is(readlink("$dir/sub/link"), $expected_target, 'readlink preserves relative target');
    unlink $target or die "unlink $target: $!";
    is(readlink("$dir/sub/link"), $expected_target, 'readlink works for broken symlink');
    ok(unlink("$dir/sub/link"), 'unlink removes broken symlink');
    ok(!unlink($dir), 'unlink does not remove directories');
}

done_testing;
