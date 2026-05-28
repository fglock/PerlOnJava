package Module::List;

use strict;
use warnings;
use File::Find ();

our $VERSION = '0.005';

sub list_modules {
    my ($prefix, $opts) = @_;
    $prefix //= '';
    $opts ||= {};

    my $rel_prefix = $prefix;
    $rel_prefix =~ s{::}{/}g;
    my %mods;

    for my $inc (@INC) {
        next if !defined $inc || $inc eq '' || $inc =~ /^jar:/;
        my $root = length($rel_prefix) ? "$inc/$rel_prefix" : $inc;
        next unless -d $root;

        if ($opts->{recurse}) {
            File::Find::find({
                wanted => sub {
                    return unless /\.pm\z/ && -f $_;
                    my $path = $File::Find::name;
                    my $rel = substr($path, length($inc) + 1);
                    $rel =~ s{\.pm\z}{};
                    $rel =~ s{/}{::}g;
                    $mods{$rel} = $path;
                },
                no_chdir => 1,
            }, $root);
        }
        else {
            opendir my $dh, $root or next;
            while (defined(my $ent = readdir $dh)) {
                next unless $ent =~ /\.pm\z/;
                my $path = "$root/$ent";
                next unless -f $path;
                (my $name = $ent) =~ s/\.pm\z//;
                $mods{$prefix . $name} = $path;
            }
            closedir $dh;
        }
    }

    return \%mods;
}

1;
