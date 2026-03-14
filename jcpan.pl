#!perl
#
# jcpan.pl - CPAN Client for PerlOnJava
# A user-friendly wrapper for installing CPAN modules
#
# Usage:
#   jcpan install Module::Name    Install a module (skips tests)
#   jcpan test Module::Name       Install with tests
#   jcpan search pattern          Search CPAN for modules
#   jcpan info Module::Name       Show module information
#   jcpan help                    Show this help
#

use strict;
use warnings;
use CPAN;

# ANSI color codes (only if terminal)
my $USE_COLOR = -t STDOUT;
my $RED    = $USE_COLOR ? "\e[0;31m" : "";
my $GREEN  = $USE_COLOR ? "\e[0;32m" : "";
my $YELLOW = $USE_COLOR ? "\e[1;33m" : "";
my $BLUE   = $USE_COLOR ? "\e[0;34m" : "";
my $NC     = $USE_COLOR ? "\e[0m"    : "";

sub show_help {
    print <<"END_HELP";
jcpan - CPAN Client for PerlOnJava

Usage:
  jcpan install <module> [module2 ...]   Install modules (skips tests)
  jcpan test <module> [module2 ...]      Install modules with tests
  jcpan search <pattern>                 Search CPAN for modules
  jcpan info <module>                    Show module information
  jcpan help                             Show this help

Options:
  --force    Force reinstall even if module is installed

Examples:
  jcpan install Moo
  jcpan install Path::Tiny Class::Tiny
  jcpan search XML::

Notes:
  - Modules are installed to ~/.perlonjava/lib/
  - Only pure Perl modules are supported (no XS/C)
  - Tests are skipped by default (use 'test' command to run them)
END_HELP
}

sub install_modules {
    my ($with_test, @args) = @_;
    my $force = 0;
    my @modules;
    
    for my $arg (@args) {
        if ($arg eq '--force') {
            $force = 1;
        } elsif ($arg =~ /^-/) {
            die "${RED}Unknown option: $arg${NC}\n";
        } else {
            push @modules, $arg;
        }
    }
    
    unless (@modules) {
        die "${RED}Error: No module specified${NC}\nUsage: jcpan install <module> [module2 ...]\n";
    }
    
    for my $module (@modules) {
        print "${BLUE}Installing $module...${NC}\n";
        
        if ($with_test eq 'notest') {
            if ($force) {
                CPAN::Shell->force('notest', 'install', $module);
            } else {
                CPAN::Shell->notest('install', $module);
            }
        } else {
            if ($force) {
                CPAN::Shell->force('install', $module);
            } else {
                CPAN::Shell->install($module);
            }
        }
        
        print "${GREEN}Done with $module${NC}\n";
    }
}

sub search_modules {
    my ($pattern) = @_;
    
    unless (defined $pattern && $pattern ne '') {
        die "${RED}Error: No search pattern specified${NC}\nUsage: jcpan search <pattern>\n";
    }
    
    print "${BLUE}Searching CPAN for '$pattern'...${NC}\n";
    CPAN::Shell->m("/$pattern/");
}

sub show_info {
    my ($module) = @_;
    
    unless (defined $module && $module ne '') {
        die "${RED}Error: No module specified${NC}\nUsage: jcpan info <module>\n";
    }
    
    print "${BLUE}Module information for $module:${NC}\n";
    my $mod = CPAN::Shell->expand('Module', $module);
    if ($mod) {
        print "Module:    ", $mod->id, "\n";
        print "Version:   ", ($mod->cpan_version || 'unknown'), "\n";
        print "Installed: ", ($mod->inst_version || 'not installed'), "\n";
        print "File:      ", ($mod->cpan_file || 'unknown'), "\n";
    } else {
        print "Module '$module' not found in CPAN index\n";
    }
}

# Main
my $command = shift @ARGV // 'help';

if ($command eq 'install' || $command eq 'i') {
    install_modules('notest', @ARGV);
}
elsif ($command eq 'test' || $command eq 't') {
    install_modules('test', @ARGV);
}
elsif ($command eq 'search' || $command eq 's') {
    search_modules($ARGV[0]);
}
elsif ($command eq 'info') {
    show_info($ARGV[0]);
}
elsif ($command eq 'help' || $command eq '--help' || $command eq '-h') {
    show_help();
}
else {
    print STDERR "${RED}Unknown command: $command${NC}\n\n";
    show_help();
    exit 1;
}
